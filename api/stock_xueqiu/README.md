# stock_xueqiu

雪球行情 / K 线 / 分钟线接口的 REST Client 调试样例。

## 环境要求

- 支持 REST Client 的 IDE（VS Code REST Client 插件 / JetBrains HTTP Client）。
- 在同目录 `.env` 中配置 `XUEQIU` 登录 Cookie（模板见 `.env.example`）。

## 快速开始

1. 复制 `.env.example` 为 `.env`，填入 `XUEQIU` Cookie。
2. 打开任一 `.http` 文件发送请求；行情接口请求头为 `Cookie: {{$dotenv XUEQIU}}`。

## 接口清单

| 文件 | 方法 | 路径 | 说明 |
|---|---|---|---|
| `quote.http` | GET | `/v5/stock/quote.json?symbol=&extend=detail` | 个股行情报价（详情） |
| `quotec.http` | GET | `/v5/stock/realtime/quotec.json?symbol=` | 实时报价 |
| `minute_1d.http` | GET | `/v5/stock/chart/minute.json?symbol=&period=1d` | 当日分钟线 |
| `minute_5d.http` | GET | `/v5/stock/chart/minute.json?symbol=&period=5d` | 近 5 日分钟线 |
| `kline.http` | GET | `/v5/stock/chart/kline.json?symbol=&begin=&end=&period=1m` | K 线（含起止时间戳、周期） |

## 功能 / 行为说明

- 基址为 `https://stock.xueqiu.com`；样例股票代码 `SH603533`，可替换为其他代码。
- `begin` / `end` 为毫秒时间戳；`period` 控制 K 线周期。
- 多数接口需带登录 Cookie，未登录或 Cookie 失效会被拦截 / 返回未授权。
