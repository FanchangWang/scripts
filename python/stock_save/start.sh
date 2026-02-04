#!/bin/sh

echo "检查 docker compose 服务状态..."
if docker compose ps -q | grep -q .; then
    echo "服务已运行，正在重启..."
    docker compose restart
else
    echo "服务未运行，正在启动..."
    docker compose up -d
fi
echo "操作完成！"
