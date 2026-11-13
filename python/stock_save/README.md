# stock_save

雪球个股分钟线数据保存 + Web 图表展示服务：定时抓取分钟线按日期落盘，FastAPI 提供网页图表与 Cookie 配置，保存 / 失败时通过 wxPusher 通知。

## 环境要求

- Python ≥ 3.12
- 依赖：`fastapi>=0.143.0`、`uvicorn>=0.54.0`、`httpx>=0.28.1`、`jinja2>=3.1.6`、`python-dotenv>=1.2.4`
- 使用 uv 管理虚拟环境；Web 图表使用本地 Chart.js（`static/`），无需联网 CDN。

## 快速开始

### 本地运行

```bash
uv sync

# 抓取一次分钟线并落盘（CLI 任务）
uv run cli

# 启动 Web 服务（0.0.0.0:8000）
uv run web
```

### Docker Compose 部署

```powershell
# Windows（PowerShell）
.\run.ps1

# Linux / macOS
./run.sh
```

脚本会按需编译镜像并 `docker compose up -d`；容器内 Web 端口 8000，Compose 映射到宿主机 `8810`。

## 配置

`.env`（模板见 `.env.example`）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `STOCK_SYMBOL` | `SH603533` | 股票代码 |
| `WXPUSHER_APP_TOKEN` | 空 | wxPusher appToken |
| `WXPUSHER_UIDS` | 空 | wxPusher 接收人 uids |

Cookie 存放在 `config/cookie.json`：`xq_a_token`、`u`，可在网页「Cookie」页填写 / 测试，或手动放置。

## 用法 / 参数

### CLI（`uv run cli`）

读取 `config/cookie.json` → 调雪球接口取分钟线 → 按日期存 `data/YYYY-MM-DD.json` → wxPusher 通知：

- 文件已存在且内容相同：跳过保存并通知「已存在」。
- 文件已存在但内容不同：覆盖保存并通知「已覆盖」。
- Cookie 未配置 / 获取失败：记录日志并推送失败通知。

### Web 页面与接口

| 路径 | 方法 | 说明 |
|---|---|---|
| `/` | GET | 首页，列出最近 5 个数据文件 |
| `/cookie` | GET | Cookie 配置 / 测试页 |
| `/chart` | GET | 分钟线图表页（近 4 周） |
| `/api/stock-data` | GET | 返回周 / 日维度分钟线 JSON |
| `/api/files/recent` | GET | 最近文件列表 |
| `/api/cookie` | PUT | 保存 Cookie 到 `config/cookie.json` |
| `/api/cookie/test` | POST | 用填写的 Cookie 测试取数，有效时按规则落盘当天数据 |

## 功能 / 行为说明

- 数据按交易日存为 `data/YYYY-MM-DD.json`；图表按本周 / 上周 / 前几周聚合工作日（周一至周五）。
- 定时落盘：Docker 容器内 crontab 为 `20 15 * * 1-5`（工作日 15:20）执行 `uv run cli`；本地需自行安排定时调用 `cli`。
- 部署方式：Docker Compose（`docker-compose.yml`）或 supervisord（`supervisord.conf`，同时拉起 crond 与 web）。
- 日志写入 `log/`；`data/`、`config/`、`log/` 在 Compose 中挂载为卷。

## 开发

- 源码在 `src/stock_save/`：`cli.py`（落盘任务）、`web/`（FastAPI 路由与模板）、`xueqiu.py`（雪球客户端）、`config.py` / `models.py` / `notification.py` / `logger.py`。
- 入口脚本：`cli = stock_save.cli:main`，`web = stock_save.web.app:run`（见 `pyproject.toml`）。
