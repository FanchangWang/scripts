# stock

股票实时监控青龙脚本：交易时段拉取实时行情，判断交易事件类型，并按持仓成本计算盈亏。

## 环境要求

- Python 3，依赖见 `requirements.txt`：`requests`、`python-dotenv`。
- 青龙面板环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `STOCK_SYMBOL` | `SH603533` | 股票代码 |
| `STOCK_BUY_PRICE` | `0` | 买入成本价（元 / 股），填 0 则只看现价涨跌 |
| `STOCK_BUY_COUNT` | `0` | 买入股数，填 0 则不计算持仓盈亏 |

## 快速开始

1. 安装依赖：`pip install -r requirements.txt`。
2. 在青龙面板配置上述环境变量。
3. 新建定时任务运行 `stock_monitor.py`，建议 cron：`*/5 9-11,13-15 * * *`。

本地调试：

```bash
pip install -r requirements.txt
python stock_monitor.py
```

## 用法 / 参数

- 无命令行参数；配置全部来自环境变量。
- 通知：青龙下调用 `QLAPI.notify`；本地运行直接打印日志。

## 功能 / 行为说明

- **交易时段判断**：仅在 9:15–11:30、13:00–15:00 之间继续执行，非交易时段直接结束。
- **行情获取**：请求雪球实时报价 `https://stock.xueqiu.com/v5/stock/realtime/quotec.json?symbol=<代码>`。
- **事件类型**：按行情时间戳判断为 `竞价` / `开盘` / `交易` / `休市` / `收盘`；返回数据非当天时提示「非交易日期」并结束。
- **盈亏计算**：配置了买入价与股数时，计算持仓盈亏（元、比例）与今日盈亏；否则仅显示现价与涨跌额 / 涨幅。
- 通知标题携带事件、时间、现价与盈亏摘要。

## 开发

- 入口：`stock_monitor.py`（`StockMonitor`）。
- 行情接口地址在 `api_url`，如需更换数据源在此修改。
