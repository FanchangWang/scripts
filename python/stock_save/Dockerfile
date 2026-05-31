FROM python:3.12-alpine

ENV PYTHONUNBUFFERED=1

WORKDIR /app

RUN apk add --no-cache supervisor tzdata \
    && ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

RUN --mount=type=cache,target=/root/.cache/pip \
    pip install uv --no-cache-dir

COPY pyproject.toml .

RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --system --no-dev --frozen

COPY supervisord.conf /etc/supervisord.conf
COPY src/ /app/src/
COPY static/ /app/static/

RUN mkdir -p /app/data /app/config /app/log \
    && echo "20 15 * * 1-5 cd /app && uv run cli >> /app/log/cron.log 2>&1" \
    > /var/spool/cron/crontabs/root \
    && chmod 0600 /var/spool/cron/crontabs/root

EXPOSE 8000

CMD ["supervisord", "-c", "/etc/supervisord.conf", "-n"]
