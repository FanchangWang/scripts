FROM python:3.12-alpine

ENV PYTHONUNBUFFERED=1 \
    UV_INDEX_URL=https://mirrors.tuna.tsinghua.edu.cn/pypi/web/simple

WORKDIR /app

RUN sed -i 's|dl-cdn.alpinelinux.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apk/repositories \
    && apk add --no-cache supervisor tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install uv -i https://mirrors.tuna.tsinghua.edu.cn/pypi/web/simple --no-cache-dir

# uv.lock 入库，用 --frozen 保证各平台构建版本一致
COPY pyproject.toml README.md uv.lock ./

# 第一段：只装第三方依赖。此时 /app/src 还不存在，必须跳过「安装项目自身」，
# 否则 uv_build 会报：Expected a Python module at: src/stock_save/__init__.py
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --no-dev --frozen --no-install-project

COPY supervisord.conf /etc/supervisord.conf
COPY src/ /app/src/
COPY static/ /app/static/

# 第二段：源码到位后再装项目本身（uv 默认按 editable 装，
# web/app.py 里的 static 路径是 Path(__file__).parent^4 / "static" == /app/static，依赖这一点）
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --no-dev --frozen

RUN mkdir -p /app/data /app/config /app/log \
    && echo "20 15 * * 1-5 cd /app && /app/.venv/bin/cli >> /app/log/cron.log 2>&1" \
    > /var/spool/cron/crontabs/root \
    && chmod 0600 /var/spool/cron/crontabs/root

EXPOSE 8000

CMD ["supervisord", "-c", "/etc/supervisord.conf", "-n"]
