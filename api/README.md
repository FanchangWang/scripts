# api

第三方接口调试集合：大部分是 REST Client 风格的 `.http` 请求样例，另附少量 Python 手动调试脚本。

## 项目索引

| 目录 | 技术栈 | 一句话用途 | 文档 |
|---|---|---|---|
| [ai](ai/) | REST Client `.http` | 混元 / GLM / 星火 / 阿里百炼 / DeepSeek 等大模型对话接口调试样例 | [README](ai/README.md) |
| [bjxd](bjxd/) | REST Client `.http` | 北京现代会员系统：用户 / 文章 / 答题 / 签到 / 任务 / 成绩接口样例 | [README](bjxd/README.md) |
| [oshwhub](oshwhub/) | Python requests | 立创开源平台用户信息、签到接口手动调试脚本 | [README](oshwhub/README.md) |
| [qinglong](qinglong/) | REST Client `.http` | 青龙面板认证、环境变量接口调试样例 | [README](qinglong/README.md) |
| [stock_xueqiu](stock_xueqiu/) | REST Client `.http` | 雪球行情 / K 线 / 分钟线接口样例 | [README](stock_xueqiu/README.md) |
| [wx_pusher](wx_pusher/) | REST Client `.http` | wxPusher 微信推送消息接口样例 | [README](wx_pusher/README.md) |

## 共性说明

- **运行方式**：`.http` 文件需在支持 REST Client 的 IDE 中打开（如 VS Code REST Client 插件、JetBrains HTTP Client），点击请求上方的发送按钮即可调用；本目录没有可执行构建产物。
- **变量与密钥**：请求中用 `{{$dotenv 变量名}}` 引用同目录 `.env` 里的变量；先复制 `.env.example` 为 `.env` 填入真实值。`.env` 不入库。
- **响应样例**：每个 `.http` 文件下方以注释形式附了历史响应内容，便于对照字段。
- 除 `oshwhub/` 是 Python requests 脚本外，其余目录均为纯 `.http` 样例。
