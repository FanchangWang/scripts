# qinglong

青龙面板定时脚本集合：登录态 / 账号密码通过环境变量配置，依赖见各目录 `requirements.txt`。

## 项目索引

| 目录 | 一句话用途 | 建议 cron | 文档 |
|---|---|---|---|
| [binmt_cc](binmt_cc/) | MT 论坛签到、查积分，纯 Python 解 acw_sc__v2 WAF 盾 | `12 3 * * *` | [README](binmt_cc/README.md) |
| [bjxd](bjxd/) | 北京现代 APP 签到 / 浏览文章 / 答题自动任务（多账号 + 可选 AI 答题） | `25 6 * * *` | [README](bjxd/README.md) |
| [iptv_cc](iptv_cc/) | iptv.cc 签到、自动回帖、查积分 | `48 3 * * *` | [README](iptv_cc/README.md) |
| [oshwhub](oshwhub/) | 开源平台签到 / 7 天与月度好礼；另附抓 Cookie 的 GUI 工具 | `10 5 * * *` | [README](oshwhub/README.md) |
| [stock](stock/) | 股票实时监控，检查交易事件并计算持仓盈亏 | `*/5 9-11,13-15 * * *` | [README](stock/README.md) |

## 共性说明

- **部署**：脚本面向青龙面板，在面板中添加任务并配置对应的环境变量；依赖按各目录 `requirements.txt` 安装。
- **环境变量**：账号、密码、Token、Cookie 等在青龙面板「环境变量」中配置，不写进脚本，也不入库；模板见各目录 `.env.example`。
- **定时规则**：上表 cron 来自各脚本 docstring，可按需要在面板中调整。
- 脚本多为 `requests` + `BeautifulSoup`，部分禁用了 SSL 警告；具体字段、多账号写法与可选 AI 答题参数见各项目 README。
