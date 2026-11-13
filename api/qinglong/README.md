# qinglong

青龙面板开放接口的 REST Client 调试样例：认证取 token、查询环境变量。

## 环境要求

- 支持 REST Client 的 IDE（VS Code REST Client 插件 / JetBrains HTTP Client）。
- 在同目录 `.env` 中配置面板地址与应用凭证（模板见 `.env.example`）。

## 快速开始

1. 复制 `.env.example` 为 `.env`，填入 `BASE_URL`、`CLIENT_ID`、`CLIENT_SECRET`。
2. 先发送 `auth.http` 的请求获取 token，再发送 `envs.http` 查询环境变量。

## 接口清单

| 文件 | 方法 | 路径 | 说明 |
|---|---|---|---|
| `auth.http` | GET | `/open/auth/token?client_id=&client_secret=` | 用应用凭证换取 access token |
| `envs.http` | GET | `/open/envs?searchValue=` | 查询环境变量，需 `Authorization: Bearer <token>` |

## 功能 / 行为说明

- `envs.http` 中用 `@auth_token = {{getToken.response.body.data.token}}` 引用上一步返回的 token。
- `searchValue` 按环境变量名模糊查询，样例为 `BJXD1`，可自行修改。
- `BASE_URL` 形如 `http://ip:port`，不带末尾斜杠。
