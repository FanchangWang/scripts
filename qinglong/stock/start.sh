#!/bin/sh
set -e  # 遇到错误立即退出，避免静默失败

# 1. 安装 Alpine 必要依赖（cronie 是 cron 服务，tzdata 用于时区配置）
apk add --no-cache cronie tzdata

# 2. 配置系统时区（与环境变量 TZ 保持一致）
ln -sf /usr/share/zoneinfo/${TZ} /etc/localtime
echo "${TZ}" > /etc/timezone

# 3. 安装 Python 依赖（--no-cache-dir 减少镜像体积）
pip install --no-cache-dir -r /app/requirements.txt

# 4. 确保日志、数据目录存在
mkdir -p /app/log
mkdir -p /app/data

# 5. 配置 cron 定时任务（每天 16 点执行脚本，日志重定向到 log 目录）
# 语法：分 时 日 月 周 命令 → 0 16 * * * 表示每天16:00执行
echo "0 16 * * * python /app/stock_save_minute.py" > /etc/crontabs/root

# 6. 赋予 cron 任务文件正确权限（cronie 要求 0600 权限，更安全）
chmod 0600 /etc/crontabs/root

# 7. 前台启动 cron 服务（Docker 容器必须有前台进程，否则会立即退出）
# -f：前台运行；-d 8：输出详细日志（8=最详细，方便排查问题）
exec crond -f -s
