#!/bin/bash

# 获取脚本所在目录的绝对路径
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# 配置文件路径
CONFIG_FILE="$SCRIPT_DIR/config.yaml"
TEMP_CONFIG_FILE="$SCRIPT_DIR/config.yaml.tmp"
BACKUP_CONFIG_FILE="$SCRIPT_DIR/config.yaml.bak"
# 仓库信息
OWNER="FanchangWang"
REPO="clash_config"
GITHUB_PROXY="https://github.allproxy.dpdns.org"
API_URL="$GITHUB_PROXY/https://api.github.com/repos/$OWNER/$REPO/contents/config.yaml"
HEADERS=("Accept: application/vnd.github.v3+json")

# 函数：打印 crontab 信息
crontab_check() {
    # 获取当前的 crontab 内容
    current_crontab=$(crontab -l 2>/dev/null)

    # 定义需要添加的两个 crontab 条目
    update_cron="40 * * * * bash $SCRIPT_DIR/update_config.sh >> $SCRIPT_DIR/update_config.log 2>&1"
    cleanup_cron="10 3 */7 * * truncate -s 0 $SCRIPT_DIR/update_config.log 2>&1"

    # 检查是否已存在这些条目
    update_exists=$(echo "$current_crontab" | grep -F "$update_cron")
    cleanup_exists=$(echo "$current_crontab" | grep -F "$cleanup_cron")

    if [ -n "$update_exists" ]; then
        echo "✓ crontab 配置更新任务已设置"
    else
        echo "✗ crontab 配置更新任务未设置，需要添加："
        echo "$update_cron"
    fi

    if [ -n "$cleanup_exists" ]; then
        echo "✓ crontab 日志清理任务已设置"
    else
        echo "✗ crontab 日志清理任务未设置，需要添加："
        echo "$cleanup_cron"
    fi
}

# 函数：计算 Git SHA 值
calculate_git_sha() {
    local file_path="$1"
    {
        size=$(wc -c < "$file_path")
        printf "blob %d\0" "$size"
        cat "$file_path"
    } | sha1sum | awk '{print $1}'
}

# 函数：从 API 获取 SHA 和内容
fetch_from_api() {
    echo "从 GitHub API 获取 SHA 和内容..."
    response=$(curl -s -H "${HEADERS[@]}" "$API_URL")
    if [[ $? -ne 0 ]]; then
        echo "错误：无法从 GitHub API 获取数据。"
        exit 1
    fi

    api_sha=$(echo "$response" | grep -o '"sha": "[^"]*' | cut -d'"' -f4)
    content_base64=$(echo "$response" | grep -o '"content": "[^"]*' | cut -d'"' -f4 | sed 's/\\n/\n/g')
    content=$(echo "$content_base64" | base64 --decode)

    if [[ -z "$api_sha" || -z "$content" ]]; then
        echo "错误：无法从 API 响应中解析 sha 或 content。"
        exit 1
    fi

    echo "API SHA: $api_sha"
    echo "API content length: $(echo "$content" | wc -c)"
}

# 函数：读取本地文件的 SHA 值
get_local_sha() {
    if [[ -f "$CONFIG_FILE" ]]; then
        calculate_git_sha "$CONFIG_FILE"
    else
        echo ""
    fi
}

# 函数：备份并更新文件
backup_and_update() {
    echo "正在备份当前的 $CONFIG_FILE 到 $BACKUP_CONFIG_FILE..."
    cp "$CONFIG_FILE" "$BACKUP_CONFIG_FILE"

    echo "正在用新内容更新 $CONFIG_FILE..."
    echo "$content" > "$TEMP_CONFIG_FILE"
    mv "$TEMP_CONFIG_FILE" "$CONFIG_FILE"
}

# 函数：验证更新后的文件 SHA
validate_updated_file() {
    local updated_sha=$(get_local_sha)
    if [[ "$updated_sha" != "$api_sha" ]]; then
        echo "错误：新配置文件 SHA 不匹配 ( 新配置文件值：$updated_sha, API 值：$api_sha )。"
        echo "恢复备份..."
        mv "$BACKUP_CONFIG_FILE" "$CONFIG_FILE"
        echo "备份已恢复。"
        exit 1
    fi
    echo "新配置文件 SHA 验证成功。"
}

# 主逻辑
echo "================================================"
echo "时间：$(TZ='Asia/Shanghai' date +%Y%m%d\ %H:%M:%S)"

echo -e "\n检查 crontab 是否配置..."
crontab_check

echo -e "\n开始执行配置更新..."
fetch_from_api

local_sha=$(get_local_sha)
echo "本地配置文件 SHA: $local_sha"

if [[ "$local_sha" == "$api_sha" ]]; then
    echo "本地配置文件是最新的，无需更新。"
    exit 0
fi

backup_and_update
validate_updated_file

echo "重启 mihomo service..."
systemctl restart mihomo

echo "脚本执行完毕。"
