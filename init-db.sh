#!/bin/bash

# ============================================
# XXL-JOB 调度中心数据库初始化脚本（PostgreSQL，可重复执行）
# 功能:
#   1. 连接 PostgreSQL（管理员账号），按需创建应用账号与 xxl_job 库
#   2. 库为空时执行 doc/db/tables_xxl_job.sql 全量建表（含默认数据：admin/123456、示例执行器组与任务）
#   3. 执行 doc/db/migration/NNN_*.sql 中尚未执行过的增量迁移（按 xxl_job_schema_migration 表去重）
# 用法: ./init-db.sh [--env=<file>] [--profile=<name>] [--admin-user=<superuser>] [--drop] [--yes] [--migrate-only]
#   --env=<file>          从 env 文件读取 DB_URL / DB_USER / DB_PASSWORD（与 start.sh 相同格式）
#   --profile=<name>      等价于 --env=xxl-job-admin/.env.<name>，文件不存在即报错
#   不带以上两项时默认加载 xxl-job-admin/.env（等价于 --env=xxl-job-admin/.env）
#   --admin-user=<name>   建账号/建库所用的超级用户。默认：应用账号是当前 OS 用户则用它自己，否则用 postgres
#                         管理员密码通过环境变量 PGADMIN_PASSWORD 传入（本机免密可不传）
#   --drop                先删掉已存在的库再重建（丢数据！需交互确认或 --yes）
#   --yes                 跳过 --drop 的交互确认
#   --migrate-only        不建账号建库、不跑全量脚本，只补增量迁移（给已有库升级用）
#
# 连 .env 都没有时用内置本机默认值：127.0.0.1:5432/xxl_job，应用账号 = 当前 OS 用户（Homebrew PostgreSQL 免密）
# 生产：./init-db.sh --admin-user=sentinopg （PGADMIN_PASSWORD=... 前置；OCI 托管 PG 管理员是 sentinopg，非 postgres）
# 注意: 数据库服务本身不由本脚本启动，本机请先 `brew services start postgresql@14`
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

log_info()    { echo -e "${BLUE}ℹ️  $1${NC}"; }
log_success() { echo -e "${GREEN}✅ $1${NC}"; }
log_warning() { echo -e "${YELLOW}⚠️  $1${NC}"; }
log_error()   { echo -e "${RED}❌ $1${NC}"; }
print_separator() { echo -e "${BLUE}================================================${NC}"; }

# 项目配置
PROJECT_NAME="XXL-JOB 调度中心"
ADMIN_MODULE="xxl-job-admin"
SCHEMA_SQL="doc/db/tables_xxl_job.sql"
MIGRATION_DIR="doc/db/migration"
MIGRATION_TABLE="xxl_job_schema_migration"
MARKER_TABLE="xxl_job_info"          # 存在即视为已初始化

# 参数
PROFILE_SET=false; [ -n "${PROFILE:-}" ] && PROFILE_SET=true
PROFILE="${PROFILE:-local}"
ENV_FILE=""
ADMIN_USER=""
DROP=false
YES=false
MIGRATE_ONLY=false

check_command() {
    if ! command -v "$1" &> /dev/null; then
        log_error "$1 未安装，请先安装 PostgreSQL 客户端（macOS: brew install postgresql@14）"
        exit 1
    fi
}

# jdbc:postgresql://host:port/db?params → "host port db"
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

# psql 快捷方式：以管理员连 postgres 库 / 以应用账号连业务库
# -w：绝不交互式询问密码。否则密码缺失时 psql 会弹 "Password for user xxx:"，
# 输进去的内容对脚本内部的连接检查无效，只会让人误以为密码错了。
psql_admin() {
    PGPASSWORD="${PGADMIN_PASSWORD:-}" psql -h "$DB_HOST" -p "$DB_PORT" -U "$ADMIN_USER" -d postgres -w -v ON_ERROR_STOP=1 -X -q "$@"
}
psql_app() {
    PGPASSWORD="${DB_PASSWORD:-}" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -w -v ON_ERROR_STOP=1 -X -q "$@"
}
table_exists() {
    psql_app -tAc "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='$1'" | grep -q 1
}

# 步骤1: 加载配置
step_load_env() {
    print_separator
    log_info "步骤 1/5: 加载数据库配置 (profile: $PROFILE)..."
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
        # 只取数据库三项，避免把 JAVA_OPTS 等无关变量带进来
        set -a
        # shellcheck disable=SC1090
        eval "$(grep -E '^(DB_URL|DB_USER|DB_PASSWORD)=' "$candidate" || true)"
        set +a
    else
        log_info "未找到 ${ADMIN_MODULE}/.env，使用内置本机默认值（本机 PostgreSQL 5432 / 当前 OS 用户免密）"
    fi

    DB_URL="${DB_URL:-jdbc:postgresql://127.0.0.1:5432/xxl_job}"
    DB_USER="${DB_USER:-$(whoami)}"
    DB_PASSWORD="${DB_PASSWORD-}"
    read -r DB_HOST DB_PORT DB_NAME <<< "$(parse_db_url "$DB_URL")"
    # 允许用环境变量单独覆盖库名（如临时建测试库）
    DB_NAME="${DB_NAME_OVERRIDE:-$DB_NAME}"

    if [ -z "$ADMIN_USER" ]; then
        if [ "$DB_USER" = "$(whoami)" ]; then ADMIN_USER="$DB_USER"; else ADMIN_USER="postgres"; fi
    fi

    echo "   ├─ 目标        : ${DB_HOST}:${DB_PORT}/${DB_NAME}"
    echo "   ├─ 应用账号    : ${DB_USER} $([ -n "$DB_PASSWORD" ] && echo '(有密码)' || echo '(无密码)')"
    echo "   ├─ 管理员账号  : ${ADMIN_USER} $([ -n "${PGADMIN_PASSWORD:-}" ] && echo '(有密码)' || echo '(无密码 / 免密)')"
    echo "   └─ 模式        : $([ "$MIGRATE_ONLY" = true ] && echo '只补迁移' || echo '建账号/建库/建表/迁移')$([ "$DROP" = true ] && echo ' + 删库重建' || true)"
    log_success "配置加载完成"
    echo ""
}

# 步骤2: 连接检查
step_check_connection() {
    print_separator
    log_info "步骤 2/5: 检查 PostgreSQL 连接..."
    print_separator

    check_command "psql"
    if ! pg_isready -h "$DB_HOST" -p "$DB_PORT" -t 5 > /dev/null 2>&1; then
        log_error "PostgreSQL 未就绪: ${DB_HOST}:${DB_PORT}"
        [ "$DB_HOST" = "127.0.0.1" ] || [ "$DB_HOST" = "localhost" ] && log_error "本机请先执行: brew services start postgresql@14"
        exit 1
    fi
    if [ "$MIGRATE_ONLY" = false ]; then
        # 连远端库、管理员又不是当前 OS 用户，几乎不可能免密；缺密码就直接说清楚，不去撞一次连接
        if [ -z "${PGADMIN_PASSWORD:-}" ] && [ "$DB_HOST" != "127.0.0.1" ] && [ "$DB_HOST" != "localhost" ]; then
            log_error "远端库 ${DB_HOST} 的管理员 ${ADMIN_USER} 需要密码，但 PGADMIN_PASSWORD 为空"
            log_error "用法: PGADMIN_PASSWORD='<管理员密码>' ./init-db.sh --env=... --admin-user=${ADMIN_USER}"
            exit 1
        fi
        if ! psql_admin -tAc "SELECT 1" > /dev/null 2>&1; then
            log_error "无法以管理员 ${ADMIN_USER} 连接 ${DB_HOST}:${DB_PORT}/postgres（密码请用 PGADMIN_PASSWORD 传入，或 --admin-user 指定其他超级用户）"
            exit 1
        fi
        log_success "管理员连接正常 (${ADMIN_USER}@${DB_HOST}:${DB_PORT})"
    fi
    echo ""
}

# 步骤3: 账号与库
step_role_and_database() {
    print_separator
    log_info "步骤 3/5: 检查应用账号与数据库..."
    print_separator

    if [ "$MIGRATE_ONLY" = true ]; then
        log_info "只补迁移模式，跳过建账号与建库"
        echo ""
        return
    fi

    # 3.1 应用账号
    if [ "$DB_USER" = "$ADMIN_USER" ]; then
        log_info "应用账号与管理员相同 (${DB_USER})，跳过建账号"
    elif psql_admin -tAc "SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'" | grep -q 1; then
        log_success "应用账号 ${DB_USER} 已存在"
    else
        if [ -z "$DB_PASSWORD" ]; then
            log_error "需要新建账号 ${DB_USER} 但 DB_PASSWORD 为空，请在 env 文件里填好密码后重试"
            exit 1
        fi
        # 密码里的单引号转义
        local pw_escaped="${DB_PASSWORD//\'/\'\'}"
        psql_admin -c "CREATE ROLE \"${DB_USER}\" LOGIN PASSWORD '${pw_escaped}'"
        log_success "已创建应用账号 ${DB_USER}（密码取自 DB_PASSWORD）"
    fi

    # 3.2 删库重建（危险）
    local exists
    exists="$(psql_admin -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -c 1 || true)"
    if [ "$DROP" = true ] && [ "$exists" = "1" ]; then
        log_warning "即将删除数据库 ${DB_HOST}:${DB_PORT}/${DB_NAME}，所有任务、执行器组、调度日志都会丢失！"
        if [ "$YES" != true ]; then
            read -p "请输入库名 ${DB_NAME} 以确认删除: " -r
            if [ "$REPLY" != "$DB_NAME" ]; then
                log_error "输入不匹配，已取消"
                exit 1
            fi
        fi
        psql_admin -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='${DB_NAME}' AND pid <> pg_backend_pid()" > /dev/null
        psql_admin -c "DROP DATABASE \"${DB_NAME}\""
        log_success "已删除数据库 ${DB_NAME}"
        exists=0
    fi

    # 3.3 建库
    if [ "$exists" = "1" ]; then
        log_success "数据库 ${DB_NAME} 已存在"
    else
        # 托管 PG（如 OCI 的 sentinopg）管理员不是 superuser：建 OWNER 为其他角色的库前，
        # 管理员必须先成为该角色成员（PG16 起 CREATE DATABASE OWNER 要求）。本机 superuser 跑这句无害。
        if [ "$DB_USER" != "$ADMIN_USER" ]; then
            psql_admin -c "GRANT \"${DB_USER}\" TO CURRENT_USER" > /dev/null
        fi
        psql_admin -c "CREATE DATABASE \"${DB_NAME}\" OWNER \"${DB_USER}\" ENCODING 'UTF8'"
        log_success "已创建数据库 ${DB_NAME}（OWNER ${DB_USER}）"
    fi

    # 3.4 应用账号能连上
    if ! psql_app -tAc "SELECT 1" > /dev/null 2>&1; then
        log_error "应用账号 ${DB_USER} 无法连接 ${DB_NAME}（密码不对？pg_hba 未放通？）"
        exit 1
    fi
    log_success "应用账号连接正常"
    echo ""
}

# 步骤4: 全量建表
step_schema() {
    print_separator
    log_info "步骤 4/5: 全量建表 (${SCHEMA_SQL})..."
    print_separator

    [ -f "$SCHEMA_SQL" ] || { log_error "找不到 ${SCHEMA_SQL}"; exit 1; }

    if [ "$MIGRATE_ONLY" = true ]; then
        log_info "只补迁移模式，跳过全量建表"
    elif table_exists "$MARKER_TABLE"; then
        log_success "库已初始化过（存在 ${MARKER_TABLE} 表），跳过全量建表；增量变更由下一步迁移处理"
    else
        # 建表脚本前半段是普通 DDL，后半段默认数据自带 BEGIN/COMMIT，因此这里不再套 -1
        log_info "执行全量建表脚本（含默认数据）..."
        if ! psql_app -f "$SCHEMA_SQL" > /dev/null; then
            log_error "建表失败。库可能处于半初始化状态，可用 ./init-db.sh --drop 删库重建"
            exit 1
        fi
        # 全量脚本已包含所有迁移内容，把迁移记录一次性标记为已执行，避免以后重复跑
        psql_app -c "CREATE TABLE IF NOT EXISTS ${MIGRATION_TABLE} (filename VARCHAR(255) NOT NULL PRIMARY KEY, applied_at TIMESTAMP NOT NULL DEFAULT now())" > /dev/null
        local f
        for f in "${MIGRATION_DIR}"/*.sql; do
            [ -f "$f" ] || continue
            psql_app -c "INSERT INTO ${MIGRATION_TABLE}(filename) VALUES ('$(basename "$f")') ON CONFLICT (filename) DO NOTHING" > /dev/null
        done
        log_success "全量建表完成（默认数据：管理员 admin / 123456、示例执行器组与示例任务）"
    fi
    echo ""
}

# 步骤5: 增量迁移
step_migrations() {
    print_separator
    log_info "步骤 5/5: 增量迁移 (${MIGRATION_DIR})..."
    print_separator

    if ! table_exists "$MARKER_TABLE"; then
        log_error "库里没有 ${MARKER_TABLE} 表，请先不带 --migrate-only 完成初始化"
        exit 1
    fi
    psql_app -c "CREATE TABLE IF NOT EXISTS ${MIGRATION_TABLE} (filename VARCHAR(255) NOT NULL PRIMARY KEY, applied_at TIMESTAMP NOT NULL DEFAULT now())" > /dev/null

    local f name applied=0 skipped=0
    for f in "${MIGRATION_DIR}"/*.sql; do
        [ -f "$f" ] || continue
        name="$(basename "$f")"
        if psql_app -tAc "SELECT 1 FROM ${MIGRATION_TABLE} WHERE filename='${name}'" | grep -q 1; then
            echo "   ├─ 已执行，跳过: ${name}"
            skipped=$((skipped + 1))
            continue
        fi
        log_info "执行迁移: ${name}"
        # 迁移脚本要求幂等；单事务执行，出错整体回滚
        if ! psql_app -1 -f "$f" > /dev/null; then
            log_error "迁移 ${name} 失败，已回滚，请查看脚本与上方 psql 报错"
            exit 1
        fi
        # 脚本自身通常会写记录，这里兜底一次
        psql_app -c "INSERT INTO ${MIGRATION_TABLE}(filename) VALUES ('${name}') ON CONFLICT (filename) DO NOTHING" > /dev/null
        applied=$((applied + 1))
    done
    log_success "迁移完成：新执行 ${applied} 个，跳过 ${skipped} 个"
    echo ""
}

show_summary() {
    local tables
    tables="$(psql_app -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")"
    print_separator
    log_success "🎉 ${PROJECT_NAME} 数据库就绪！"
    print_separator
    echo ""
    echo -e "${GREEN}📊 数据库信息:${NC}"
    echo "   ├─ JDBC      : jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"
    echo "   ├─ 应用账号  : ${DB_USER}"
    echo "   ├─ 表数量    : ${tables}"
    echo "   └─ 已执行迁移: $(psql_app -tAc "SELECT string_agg(filename, ', ' ORDER BY filename) FROM ${MIGRATION_TABLE}")"
    echo ""
    echo -e "${GREEN}📌 下一步:${NC}"
    echo "   1. ./start.sh                       # 编译并启动调度中心 (:9280)"
    echo "   2. 浏览器打开 http://localhost:9280/ ，用 admin / 123456 登录后立刻改密码"
    echo "   3. 「执行器管理」里为 http-executor 组生成 AccessToken，填入 .env 的 XXL_JOB_EXECUTOR_ACCESS_TOKEN"
    echo ""
    echo -e "${YELLOW}💡 提示:${NC}"
    echo "   - 本脚本可重复执行：已初始化的库只会补增量迁移"
    echo "   - 以后新增迁移脚本放到 ${MIGRATION_DIR}/NNN_描述.sql，再跑一次 ./init-db.sh 即可"
    echo ""
    print_separator
}

main() {
    echo ""
    print_separator
    echo -e "${BLUE}🗄️  ${PROJECT_NAME} 数据库初始化脚本${NC}"
    print_separator
    echo ""

    for arg in "$@"; do
        case $arg in
            --env=*)        ENV_FILE="${arg#*=}" ;;
            --profile=*)    PROFILE="${arg#*=}"; PROFILE_SET=true ;;
            --admin-user=*) ADMIN_USER="${arg#*=}" ;;
            --drop)         DROP=true ;;
            --yes|-y)       YES=true ;;
            --migrate-only) MIGRATE_ONLY=true ;;
            -h|--help)      sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
            *) log_error "未知参数: $arg (支持 --env= --profile= --admin-user= --drop --yes --migrate-only)"; exit 1 ;;
        esac
    done

    step_load_env
    step_check_connection
    step_role_and_database
    step_schema
    step_migrations
    show_summary
}

main "$@"
