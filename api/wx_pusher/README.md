# wx_pusher

wxPusher 微信消息推送接口的 REST Client 调试样例。

## 环境要求

- 支持 REST Client 的 IDE（VS Code REST Client 插件 / JetBrains HTTP Client）。
- 在同目录 `.env` 中配置 `appToken` 与 `uids`（模板见 `.env.example`）。

## 快速开始

1. 复制 `.env.example` 为 `.env`，填入 wxPusher 的 `appToken` 与接收人 `uids`。
2. 打开 `message.http`，点击发送按钮推送测试消息。

## 接口清单

| 文件 | 方法 | 路径 | 说明 |
|---|---|---|---|
| `message.http` | POST | `https://wxpusher.zjiecode.com/api/send/message` | 发送一条消息 |

## 功能 / 行为说明

- 请求体关键字段：`appToken`、`content`（HTML 内容）、`summary`（摘要）、`contentType: 2`（HTML）、`uids`（数组）、`verifyPayType`。
- `uids` 接收多个用户时按数组填写；`content` 支持 HTML 标签。
- 文件下方注释附了历史响应样例，`code: 1000` 表示处理成功。
