#!/bin/bash

# ============================================
# XXL-JOB 调度中心 (xxl-job-admin) 停止脚本
# 功能: 停止由 start.sh 启动的调度中心进程（内嵌 HTTP 执行器随进程一起停）；不管数据库
# 用法: ./stop.sh [--force] [--port=<port>]
#   --force        跳过优雅停机，直接 kill -9
#   --port=9280    指定实例端口（默认取 SERVER_PORT，再默认 9280）
#
# 做法对齐 DragonFlow/stop.sh：三路收集候选 PID（pid 文件 / jar 名 / 端口占用者）→
# 逐个校验归属（进程工作目录必须是本仓库）→ TERM 等待 → KILL → 终态校验，
# 失败时诚实返回非 0，restart 类脚本据此判断。
# ============================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 日志函数
log_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

log_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

log_error() {
    echo -e "${RED}❌ $1${NC}"
}

print_separator() {
    echo -e "${BLUE}================================================${NC}"
}

# 项目配置（与 start.sh 保持一致）
PROJECT_NAME="XXL-JOB 调度中心"
RUN_DIR="local/run"
JAR_PATTERN="java .*xxl-job-admin/target/xxl-job-admin-.*\.jar"
# 调度中心停机要等时间轮排空、内嵌执行器等运行中的任务收尾，systemd 里 TimeoutStopSec=60，这里同步
GRACEFUL_WAIT=60

# ---------------------------------------------------------------------------
# 归属判定：候选 PID 只有确认属于本仓库才会被杀。
# start.sh 用相对路径起 jar，cmdline 里没有仓库绝对路径，可靠判据是进程工作目录。
# 返回: 0=属于本仓库   1=明确不属于   2=无法判定（通常是权限不足）
# ---------------------------------------------------------------------------
ownership_of() {
    local pid="${1:-}"
    [ -n "$pid" ] || return 1
    ps -p "$pid" >/dev/null 2>&1 || return 1

    # 1) Linux：/proc/<pid>/cwd；读不到再试一次非交互 sudo
    if [ -d /proc ]; then
        local cwd
        cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
        if [ -z "$cwd" ] && command -v sudo >/dev/null 2>&1; then
            cwd="$(sudo -n readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
        fi
        if [ -n "$cwd" ]; then
            [ "$cwd" = "$ROOT_DIR" ] && return 0 || return 1
        fi
    fi

    # 2) cmdline 里含本仓库绝对路径（手工用绝对路径启动的情况）
    local args
    args="$(ps -p "$pid" -o args= 2>/dev/null || true)"
    if [ -n "$args" ]; then
        case "$args" in
            *"$ROOT_DIR/"*) return 0 ;;
        esac
    fi

    # 3) macOS 本地开发：lsof 读进程 cwd
    if command -v lsof >/dev/null 2>&1; then
        local lcwd
        lcwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n1 || true)"
        if [ -n "$lcwd" ]; then
            [ "$lcwd" = "$ROOT_DIR" ] && return 0 || return 1
        fi
    fi

    return 2
}

describe_pid() {
    local pid="$1"
    echo "      属主=$(ps -p "$pid" -o user= 2>/dev/null | tr -d ' ')  当前用户=$(id -un)"
    ps -p "$pid" -o args= 2>/dev/null | head -c 200 | sed 's/^/      /' || true
    echo ""
}

# TERM → 等待 → KILL
stop_pid() {
    local pid="$1"
    if [ "$FORCE" = true ]; then
        log_warning "强制终止 (PID: $pid)..."
        kill -9 "$pid" 2>/dev/null || true
        sleep 1
        return
    fi

    log_info "正在优雅停止调度中心 (PID: $pid)，最多等待 ${GRACEFUL_WAIT}s..."
    if ! kill "$pid" 2>/dev/null; then
        log_warning "kill $pid 失败，权限不足？"
        describe_pid "$pid"
        if ps -p "$pid" > /dev/null 2>&1; then
            log_error "  → 请用 sudo 重跑：sudo ./stop.sh"
            return 0
        fi
    fi
    local wait_time=0
    while ps -p "$pid" > /dev/null 2>&1; do
        if [ "$wait_time" -ge "$GRACEFUL_WAIT" ]; then
            log_warning "应用未能在 ${GRACEFUL_WAIT}s 内正常停止，强制终止..."
            kill -9 "$pid" 2>/dev/null || true
            break
        fi
        echo -n "."
        sleep 1
        wait_time=$((wait_time + 1))
    done
    echo ""
}

stop_application() {
    print_separator
    log_info "停止 Spring Boot 应用 (端口 ${SERVER_PORT})..."
    print_separator

    # 三路收集候选，全部执行后去重：pid 文件陈旧时仍能靠 jar 名 / 端口找回真进程
    local -a CANDIDATES=()
    local pid

    # 1) pid 文件
    if [ -f "$PID_FILE" ]; then
        pid="$(head -n1 "$PID_FILE" 2>/dev/null | tr -dc '0-9' || true)"
        [ -n "$pid" ] && CANDIDATES+=("$pid")
    fi

    # 2) 按 jar 名找孤儿（pgrep 无匹配返回 1，set -e 下必须 || true）
    while IFS= read -r pid; do
        [ -n "$pid" ] && CANDIDATES+=("$pid")
    done < <(pgrep -f "$JAR_PATTERN" 2>/dev/null || true)

    # 3) 端口占用者
    while IFS= read -r pid; do
        [ -n "$pid" ] && CANDIDATES+=("$pid")
    done < <(lsof -ti "tcp:$SERVER_PORT" -sTCP:LISTEN 2>/dev/null || true)

    local -a TARGETS=()
    if [ "${#CANDIDATES[@]}" -gt 0 ]; then
        while IFS= read -r pid; do
            [ -n "$pid" ] && TARGETS+=("$pid")
        done < <(printf '%s\n' "${CANDIDATES[@]}" | sort -u)
    fi

    STOPPED_ANY=false
    SKIPPED_ANY=false
    UNKNOWN_ANY=false
    FAILED_ANY=false
    local own
    for pid in "${TARGETS[@]:-}"; do
        [ -n "$pid" ] || continue
        ps -p "$pid" > /dev/null 2>&1 || continue      # 已经没了

        ownership_of "$pid" && own=0 || own=$?
        if [ "$own" -eq 1 ]; then
            # 端口被别的服务占着：只告警不动手，宁可失败也不误杀邻居
            log_warning "PID $pid 与端口 ${SERVER_PORT} / jar 名匹配，但不属于 ${ROOT_DIR}，跳过（不杀）："
            describe_pid "$pid"
            SKIPPED_ANY=true
            continue
        elif [ "$own" -eq 2 ]; then
            log_error "PID $pid 无法确认归属（多半是权限不足），不动它："
            describe_pid "$pid"
            log_error "  → 若该进程确实属于本服务，请用 sudo 重跑：sudo ./stop.sh"
            UNKNOWN_ANY=true
            continue
        fi

        stop_pid "$pid"
        if ps -p "$pid" > /dev/null 2>&1; then
            FAILED_ANY=true
        else
            STOPPED_ANY=true
        fi
    done

    # 只有确实没有"还活着却没停掉"的进程时才删 pid 文件，否则它是找回进程的最后线索
    if [ "$FAILED_ANY" = false ] && [ "$UNKNOWN_ANY" = false ]; then
        rm -f "$PID_FILE"
    else
        log_warning "保留 $PID_FILE（仍有进程在运行，删掉会让它彻底脱管）"
    fi

    if [ "$FAILED_ANY" = true ]; then
        log_error "有进程未能停止（见上方原因）"
    elif [ "$STOPPED_ANY" = true ]; then
        log_success "调度中心已停止"
    elif [ "$UNKNOWN_ANY" = true ]; then
        log_error "有进程无法确认归属，未做任何停止操作（见上方告警）"
    elif [ "$SKIPPED_ANY" = true ]; then
        log_warning "没有属于本仓库的进程可停（端口被其它服务占用，见上方告警）"
    else
        log_warning "未找到运行中的调度中心（无 pid 文件、无匹配进程、端口 ${SERVER_PORT} 无监听）"
    fi
}

# 终态校验：只在残留进程确实属于本仓库、或查不出归属时失败
verify_stopped() {
    if [ "${UNKNOWN_ANY:-false}" = true ]; then
        log_error "停止未完成：存在无法确认归属的进程（见上方告警）"
        return 1
    fi
    if [ "${FAILED_ANY:-false}" = true ]; then
        log_error "停止未完成：有属于本仓库的进程没能杀掉（见上方告警）"
        return 1
    fi

    local pid own
    pid="$(lsof -ti "tcp:$SERVER_PORT" -sTCP:LISTEN 2>/dev/null | head -n1 || true)"
    [ -n "$pid" ] || return 0

    ownership_of "$pid" && own=0 || own=$?
    if [ "$own" -eq 0 ]; then
        log_error "仍有本服务进程占用端口 ${SERVER_PORT} (PID: $pid)"
        lsof -i "tcp:$SERVER_PORT" -sTCP:LISTEN 2>/dev/null | sed 's/^/    /' || true
        return 1
    elif [ "$own" -eq 2 ]; then
        log_error "端口 ${SERVER_PORT} 仍被占用，且无法确认归属（权限不足？）"
        describe_pid "$pid"
        log_error "  → 请用 sudo 重跑：sudo ./stop.sh"
        return 1
    fi
    return 0
}

show_stop_info() {
    print_separator
    log_success "🛑 ${PROJECT_NAME} 已停止"
    print_separator
    echo ""
    echo -e "${GREEN}💡 提示:${NC}"
    echo "   - 内嵌 HTTP 执行器随调度中心进程一起停止，注册信息已注销"
    echo "   - PostgreSQL 保持运行；本机停库用 brew services stop postgresql@14"
    echo "   - 重新启动：./start.sh（编译）或 ./start.sh --skip-compile（不编译）"
    echo ""
}

main() {
    echo ""
    print_separator
    echo -e "${BLUE}🛑 ${PROJECT_NAME} 停止脚本${NC}"
    print_separator
    echo ""

    FORCE=false
    SERVER_PORT="${SERVER_PORT:-9280}"
    for arg in "$@"; do
        case $arg in
            --force|force)
                FORCE=true
                ;;
            --port=*)
                SERVER_PORT="${arg#*=}"
                ;;
            -h|--help)
                sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'
                exit 0
                ;;
            *)
                log_error "未知参数: $arg (支持 --force --port=<port>)"
                exit 1
                ;;
        esac
    done
    PID_FILE="${RUN_DIR}/admin-${SERVER_PORT}.pid"

    stop_application
    verify_stopped
    show_stop_info
}

main "$@"
