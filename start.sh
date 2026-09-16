#!/bin/bash

# ============================================
# XXL-JOB 调度中心 (xxl-job-admin) 启动脚本
# 功能: 编译并后台启动调度中心，等待健康检查通过
# 用法: ./start.sh [--skip-compile] [--force] [--profile=<name>] [--env=<file>]
#   --skip-compile   不重新 mvn package，直接用 target 下已有 jar
#   --force          端口被占用时不询问，直接 kill 占用进程
#   --profile=<name> 只认 xxl-job-admin/.env.<name>（.env.* 已 gitignore，可放本机/测试配置）
#   --env=<file>     显式指定 env 文件（优先级最高）
#
# 配置全部走环境变量（见 xxl-job-admin/.env.example）。env 文件加载顺序：
#   1) --env=<file>
#   2) 显式 --profile=<name>（或 PROFILE 环境变量）→ xxl-job-admin/.env.<name>，不存在即报错
#   3) 默认（不带参数）→ xxl-job-admin/.env，等价于 --env=xxl-job-admin/.env
#   连 .env 都没有时使用内置默认值：本机 PostgreSQL 5432 / xxl_job 库 / 当前 OS 用户免密
#
# 注意: 数据库不由本脚本启动，本机请先 `brew services start postgresql@14`
# ============================================

set -e  # 遇到错误立即退出

# 始终以仓库根目录为工作目录，保证相对路径正确
cd "$(dirname "$0")"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 项目配置
PROJECT_NAME="XXL-JOB 调度中心"
ADMIN_MODULE="xxl-job-admin"
MIN_JAVA_VERSION=17            # 根 pom maven.compiler.target=17
MAX_WAIT_TIME=90               # 等待健康检查的最大时间(秒)，本机启动约 3 秒
RUN_DIR="local/run"            # pid 文件目录（/local/ 已 gitignore）
LOG_DIR="local/logs"           # stdout 与 LOG_HOME 默认目录

# 环境 Profile。默认不带参数时直接加载 xxl-job-admin/.env；只有显式给了 PROFILE / --profile 才去找 .env.<name>
PROFILE_SET=false; [ -n "${PROFILE:-}" ] && PROFILE_SET=true
PROFILE=${PROFILE:-local}
ENV_FILE=""

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

# 打印分隔线
print_separator() {
    echo -e "${BLUE}================================================${NC}"
}

# 检查命令是否存在
check_command() {
    if ! command -v "$1" &> /dev/null; then
        log_error "$1 未安装，请先安装 $1"
        exit 1
    fi
}

# 检查端口是否被占用
# 重新 mvn package 之前必须先停掉正在运行的同一个 jar，否则运行中的 JVM
# 会因 jar 被覆盖而报 NoClassDefFoundError；所以这一步放在编译之前。
check_port() {
    local port=$1
    local service=$2
    if lsof -Pi :"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then
        local pid_file="${RUN_DIR}/admin-${port}.pid"
        if [ -f "$pid_file" ] && lsof -ti:"$port" | grep -qx "$(cat "$pid_file")"; then
            log_warning "端口 $port 已被占用 ($service)，看起来是本脚本上次启动的实例 (PID: $(cat "$pid_file"))"
        else
            log_warning "端口 $port 已被占用 ($service)"
        fi
        if [ "$FORCE" = true ]; then
            lsof -ti:"$port" | xargs kill -9 2>/dev/null || true
            log_success "已强制停止占用端口 $port 的进程"
            sleep 2
        else
            read -p "是否要停止占用该端口的进程? (y/n) " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                lsof -ti:"$port" | xargs kill -9 2>/dev/null || true
                log_success "已停止占用端口 $port 的进程"
                sleep 2
            else
                log_error "无法启动服务，端口 $port 被占用"
                exit 1
            fi
        fi
    fi
}

# 检查Java版本
check_java_version() {
    local java_version
    java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ -z "$java_version" ] || [ "$java_version" -lt "$MIN_JAVA_VERSION" ]; then
        log_error "需要 Java ${MIN_JAVA_VERSION} 或更高版本，当前版本: ${java_version:-未知}"
        exit 1
    fi
    log_success "Java 版本检查通过: $(java -version 2>&1 | head -n 1)"
}

# 定位可执行 jar（排除 sources / javadoc）
find_admin_jar() {
    ls -t "${ADMIN_MODULE}"/target/xxl-job-admin-*.jar 2>/dev/null \
        | grep -vE -- '-(sources|javadoc)\.jar$' | head -n 1
}

# 从 JDBC URL 解析 host / port / dbname，用于 pg_isready
# jdbc:postgresql://host:port/db?params  → "host port db"
parse_db_url() {
    local url="$1" rest hostport host port db
    rest="${url#jdbc:postgresql://}"
    hostport="${rest%%/*}"
    db="${rest#*/}"; db="${db%%\?*}"
    host="${hostport%%:*}"
    port="${hostport##*:}"
    [ "$port" = "$host" ] && port=5432
    echo "${host:-localhost} ${port} ${db:-xxl_job}"
}

# 步骤0: 加载 env 文件与内置默认值
step_load_env() {
    print_separator
    log_info "步骤 1/5: 加载环境配置 (profile: $PROFILE)..."
    print_separator

    local candidate=""
    if [ -n "$ENV_FILE" ]; then
        candidate="$ENV_FILE"
        [ -f "$candidate" ] || { log_error "指定的 env 文件不存在: $candidate"; exit 1; }
    elif [ "$PROFILE_SET" = true ]; then
        # 显式指定了 --profile=<name>（或 PROFILE 环境变量）：只认 .env.<name>，不回落
        candidate="${ADMIN_MODULE}/.env.${PROFILE}"
        [ -f "$candidate" ] || { log_error "profile=$PROFILE 但找不到 $candidate"; exit 1; }
    elif [ -f "${ADMIN_MODULE}/.env" ]; then
        # 默认：等价于 --env=xxl-job-admin/.env
        candidate="${ADMIN_MODULE}/.env"
    fi

    if [ -n "$candidate" ]; then
        log_info "加载 env 文件: $candidate"
        set -a
        # shellcheck disable=SC1090
        . "$candidate"
        set +a
    else
        log_info "未找到 ${ADMIN_MODULE}/.env，使用内置本机默认值（本机 PostgreSQL 5432 / 当前 OS 用户免密）"
    fi

    # 内置默认值：env 文件未给的项按本机联调习惯补齐
    export SERVER_PORT="${SERVER_PORT:-9280}"
    export DB_URL="${DB_URL:-jdbc:postgresql://127.0.0.1:5432/xxl_job}"
    export DB_USER="${DB_USER:-$(whoami)}"          # Homebrew PostgreSQL 同名 OS 用户免密
    export DB_PASSWORD="${DB_PASSWORD-}"
    # logback 默认写 /data/applogs/xxl-job/，本机没有该目录会导致启动即失败，必须给 LOG_HOME
    export LOG_HOME="${LOG_HOME:-$(pwd)/${LOG_DIR}}"
    export XXL_JOB_LARK_ENV="${XXL_JOB_LARK_ENV:-$PROFILE}"

    HEALTH_CHECK_URL="http://localhost:${SERVER_PORT}/actuator/health"

    echo "   ├─ SERVER_PORT : $SERVER_PORT"
    echo "   ├─ DB_URL      : $DB_URL"
    echo "   ├─ DB_USER     : $DB_USER"
    echo "   ├─ LOG_HOME    : $LOG_HOME"
    echo "   └─ LARK 告警   : $([ -n "$XXL_JOB_LARK_WEBHOOK_URL" ] && echo "已配置 (env=$XXL_JOB_LARK_ENV)" || echo "未配置，通道 no-op")"
    log_success "环境配置加载完成"
    echo ""
}

# 步骤1: 环境检查
step_check_environment() {
    print_separator
    log_info "步骤 2/5: 检查环境依赖..."
    print_separator

    check_command "java"
    check_command "curl"
    check_command "lsof"
    [ "$SKIP_COMPILE" = true ] || check_command "mvn"

    check_java_version

    log_success "所有依赖检查通过"
    echo ""
}

# 步骤2: 检查端口
step_check_ports() {
    print_separator
    log_info "步骤 3/5: 检查端口占用..."
    print_separator

    check_port "$SERVER_PORT" "调度中心"

    log_success "端口检查完成"
    echo ""
}

# 步骤3: 检查数据库连接
step_check_database() {
    print_separator
    log_info "步骤 4/5: 检查数据库连接..."
    print_separator

    read -r db_host db_port db_name <<< "$(parse_db_url "$DB_URL")"

    if command -v pg_isready &> /dev/null; then
        if ! pg_isready -h "$db_host" -p "$db_port" -d "$db_name" -U "$DB_USER" -t 5 > /dev/null 2>&1; then
            log_error "PostgreSQL 未就绪: ${db_host}:${db_port}/${db_name}"
            if [ "$PROFILE" = "local" ]; then
                log_error "本机请先执行: brew services start postgresql@14"
                log_error "首次使用需建库并初始化: ./init-db.sh"
            fi
            exit 1
        fi
        log_success "PostgreSQL 连接正常 (${db_host}:${db_port}/${db_name})"
    else
        log_warning "未安装 pg_isready，跳过数据库连通性检查 (${db_host}:${db_port}/${db_name})"
    fi
    echo ""
}

# 步骤4: 编译项目
step_compile_project() {
    print_separator
    log_info "步骤 5/5: 编译 Maven 项目..."
    print_separator

    # 只构建调度中心及其依赖 (xxl-job-core)，不动执行器模块；根 pom 默认已 maven.test.skip=true
    log_info "清理并编译 ${ADMIN_MODULE} 及依赖模块 (跳过测试)..."
    if mvn clean package -Dmaven.test.skip=true -q -pl "${ADMIN_MODULE}" -am; then
        log_success "项目编译成功"
    else
        log_error "项目编译失败"
        exit 1
    fi
    echo ""
}

# 步骤5: 启动应用
step_start_application() {
    print_separator
    log_info "启动 Spring Boot 应用..."
    print_separator

    local jar_file
    jar_file="$(find_admin_jar)"
    if [ -z "$jar_file" ] || [ ! -f "$jar_file" ]; then
        log_error "未找到编译后的 JAR 文件: ${ADMIN_MODULE}/target/xxl-job-admin-*.jar"
        exit 1
    fi
    log_info "使用 JAR: $jar_file"

    # 创建 pid / 日志目录
    mkdir -p "$RUN_DIR" "$LOG_DIR" "$LOG_HOME"

    # JVM 默认与 .env.example 一致; JAVA_OPTS env 可整体覆盖
    # user.timezone=Asia/Tokyo: 调度中心统一日本时间，任务级时区由任务字段控制
    : "${JAVA_OPTS:=-Xms512m -Xmx1g -XX:+UseG1GC -Duser.timezone=Asia/Tokyo -Dfile.encoding=UTF-8}"

    local stdout_log="${LOG_DIR}/admin-${SERVER_PORT}.out"
    local pid_file="${RUN_DIR}/admin-${SERVER_PORT}.pid"

    # 启动应用(后台运行); stdout 收到 local/logs/admin-<port>.out 便于排查启动期错误
    log_info "启动应用服务..."
    # shellcheck disable=SC2086
    nohup java $JAVA_OPTS -jar "$jar_file" > "$stdout_log" 2>&1 &
    local app_pid=$!
    echo "$app_pid" > "$pid_file"

    log_success "应用已启动 (PID: $app_pid)"

    # 等待应用启动
    log_info "等待应用就绪..."
    local wait_time=0
    until curl -sf "$HEALTH_CHECK_URL" 2>/dev/null | grep -q '"status":"UP"'; do
        if ! kill -0 "$app_pid" 2>/dev/null; then
            echo ""
            log_error "应用进程已退出，请查看启动日志: tail -n 100 $stdout_log"
            exit 1
        fi
        if [ "$wait_time" -ge "$MAX_WAIT_TIME" ]; then
            echo ""
            log_error "应用启动超时，请查看日志: tail -f $stdout_log"
            exit 1
        fi
        echo -n "."
        sleep 2
        wait_time=$((wait_time + 2))
    done
    echo ""

    log_success "应用已就绪"
    echo ""
}

# 显示启动信息
show_startup_info() {
    local pid_file="${RUN_DIR}/admin-${SERVER_PORT}.pid"
    local stdout_log="${LOG_DIR}/admin-${SERVER_PORT}.out"
    local app_log="${LOG_HOME}/xxl-job/xxl-job-admin.log"

    print_separator
    log_success "🎉 ${PROJECT_NAME} 启动成功！"
    print_separator
    echo ""
    echo -e "${GREEN}📊 服务信息:${NC}"
    echo "   ├─ 运行环境: $PROFILE"
    echo "   ├─ 管理后台: http://localhost:${SERVER_PORT}/"
    echo "   ├─ 健康检查: ${HEALTH_CHECK_URL}"
    echo "   ├─ 执行器回调地址: http://<本机IP>:${SERVER_PORT}/api"
    echo "   ├─ 启动日志: ${stdout_log}"
    echo "   └─ 应用日志: ${app_log}"
    echo ""
    echo -e "${GREEN}🔧 常用命令:${NC}"
    echo "   ├─ 查看日志: tail -f ${app_log}"
    echo "   ├─ 查看进程: ps -p \$(cat ${pid_file})"
    echo "   ├─ 停止服务: ./stop.sh"
    echo "   ├─ 不编译重启: ./stop.sh && ./start.sh --skip-compile"
    echo "   └─ 编译后重启: ./stop.sh && ./start.sh"
    echo ""
    echo -e "${GREEN}🧪 测试接口:${NC}"
    echo "   curl ${HEALTH_CHECK_URL}"
    echo ""
    echo -e "${YELLOW}💡 提示:${NC}"
    echo "   - 使用 Ctrl+C 不会停止后台服务，请用 ./stop.sh 停止"
    echo "   - 重新 mvn package 前务必先停掉运行中的 jar，否则运行中的 JVM 会报 NoClassDefFoundError"
    echo "   - 数据库不由本脚本管理；本机 PostgreSQL 用 brew services start/stop postgresql@14"
    echo ""
    print_separator
}

# 主函数
main() {
    echo ""
    print_separator
    echo -e "${BLUE}🚀 ${PROJECT_NAME} 启动脚本${NC}"
    print_separator
    echo ""

    # 解析参数
    SKIP_COMPILE=false
    FORCE=false
    for arg in "$@"; do
        case $arg in
            --skip-compile)
                SKIP_COMPILE=true
                ;;
            --profile=*)
                PROFILE="${arg#*=}"; PROFILE_SET=true
                ;;
            --env=*)
                ENV_FILE="${arg#*=}"
                ;;
            --force)
                FORCE=true
                ;;
            -h|--help)
                sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'
                exit 0
                ;;
            *)
                log_error "未知参数: $arg (支持 --skip-compile --force --profile=<name> --env=<file>)"
                exit 1
                ;;
        esac
    done

    if [ "$SKIP_COMPILE" = true ]; then
        log_info "跳过编译步骤，使用 target 下已有 JAR"
        echo ""
    fi
    log_info "当前环境 Profile: $PROFILE"
    echo ""

    step_load_env
    step_check_environment
    step_check_ports
    step_check_database

    if [ "$SKIP_COMPILE" = false ]; then
        step_compile_project
    else
        # 即使跳过编译，也要检查 JAR 文件是否存在
        if [ -z "$(find_admin_jar)" ]; then
            log_error "未找到编译后的 JAR 文件: ${ADMIN_MODULE}/target/xxl-job-admin-*.jar"
            log_error "请先执行编译步骤: mvn clean package -Dmaven.test.skip=true -pl ${ADMIN_MODULE} -am"
            exit 1
        fi
        print_separator
        log_info "步骤 5/5: 编译 Maven 项目..."
        print_separator
        log_success "使用已编译的 JAR 文件"
        echo ""
    fi

    step_start_application
    show_startup_info
}

# 执行主函数
main "$@"
