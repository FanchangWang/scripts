FROM python:3.12-alpine

ENV PYTHONUNBUFFERED=1 \
    UV_INDEX_URL=https://mirrors.aliyun.com/pypi/simple

WORKDIR /app

RUN sed -i 's|dl-cdn.alpinelinux.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apk/repositories \
    && apk add --no-cache supervisor tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install uv -i https://mirrors.aliyun.com/pypi/simple --no-cache-dir

# uv.lock 入库，用 --frozen 保证各平台构建版本一致
COPY pyproject.toml README.md uv.lock ./

RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --no-dev --frozen

COPY supervisord.conf /etc/supervisord.conf
COPY src/ /app/src/
COPY static/ /app/static/

RUN mkdir -p /app/data /app/config /app/log \
    && echo "20 15 * * 1-5 cd /app && /app/.venv/bin/cli >> /app/log/cron.log 2>&1" \
    > /var/spool/cron/crontabs/root \
    && chmod 0600 /var/spool/cron/crontabs/root

EXPOSE 8000

CMD ["supervisord", "-c", "/etc/supervisord.conf", "-n"]
