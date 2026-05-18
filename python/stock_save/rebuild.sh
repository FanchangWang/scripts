#!/bin/sh

echo "检查 docker compose 服务状态..."
if docker compose ps -q | grep -q .; then
    echo "服务已运行，正在停止..."
    docker compose down
else
    echo "服务未运行"
fi

echo "正在重新构建镜像..."
docker compose build --no-cache

echo "正在启动服务..."
docker compose up -d

echo "操作完成！"
