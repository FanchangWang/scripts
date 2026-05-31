#!/usr/bin/env bash
set -e

SERVICE="stock-save"
COMPOSE_FILE="docker-compose.yml"

info()  { echo -e "\033[36m[INFO]\033[0m $*"; }
warn()  { echo -e "\033[33m[WARN]\033[0m $*"; }
error() { echo -e "\033[31m[ERR]\033[0m $*"; }

is_running() {
    docker compose -f "$COMPOSE_FILE" ps --services --filter "status=running" 2>/dev/null | grep -q "$SERVICE"
}

has_image() {
    docker compose -f "$COMPOSE_FILE" images -q "$SERVICE" 2>/dev/null | grep -q .
}

read_yesno() {
    while true; do
        read -r -p "$1 [y/N] " ans
        case "$ans" in
            [yY]|[yY][eE][sS]) return 0 ;;
            ""|n|N|no)         return 1 ;;
        esac
    done
}

read_choice() {
    local prompt="$1" valid=0
    while [ $valid -eq 0 ]; do
        read -r -p "$prompt (r/s/n) " ans
        case "$ans" in
            r|R) choice="restart"; valid=1 ;;
            s|S) choice="stop";    valid=1 ;;
            n|N) choice="noop";    valid=1 ;;
        esac
    done
}

cd "$(dirname "$0")"

if ! has_image; then
    info "未检测到镜像，直接编译并启动..."
    docker compose -f "$COMPOSE_FILE" build --no-cache
    docker compose -f "$COMPOSE_FILE" up -d
elif read_yesno "是否重新编译镜像?"; then
    if is_running; then
        info "Compose 正在运行，先停止..."
        docker compose -f "$COMPOSE_FILE" down
    fi
    info "开始编译镜像..."
    docker compose -f "$COMPOSE_FILE" build --no-cache
    info "启动 Compose..."
    docker compose -f "$COMPOSE_FILE" up -d
else
    if is_running; then
        warn "Compose 当前处于运行状态"
        read_choice "请选择操作: (r)重启 / (s)停止 / (n)无操作"
        case "$choice" in
            restart)
                info "重启 Compose..."
                docker compose -f "$COMPOSE_FILE" restart
                ;;
            stop)
                info "停止 Compose..."
                docker compose -f "$COMPOSE_FILE" down
                ;;
            noop)
                info "无操作"
                ;;
        esac
    else
        info "Compose 未运行，启动中..."
        docker compose -f "$COMPOSE_FILE" up -d
    fi
fi
