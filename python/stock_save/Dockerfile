FROM python:3.12-alpine

# 设置工作目录
WORKDIR /app

# 合并 COPY 命令
COPY requirements.txt stock_save_minute.py ./

# 合并 RUN 命令：设置时区、安装依赖、创建目录、添加定时任务
RUN apk add --no-cache tzdata && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    pip install --no-cache-dir -r requirements.txt && \
    mkdir -p /app/data /app/log && \
    echo "20 15 * * * python /app/stock_save_minute.py" > /var/spool/cron/crontabs/root && \
    chmod 0600 /var/spool/cron/crontabs/root

# 启动 cron 服务
CMD ["crond", "-f"]
