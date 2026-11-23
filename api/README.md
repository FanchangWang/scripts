# api

第三方接口调试集合：`.http` 请求样例（使用 **httpYac** 格式），另附少量 Python 手动调试脚本。

## 环境要求

- **httpYac**（一种 `.http` 文件格式，兼容原 REST Client 写法）：
  - VS Code 插件：`anweber.vscode-httpyac`
  - CLI：`npm install -g httpyac`（本机已安装）
  - 文档：<https://httpyac.github.io/>
- 原 `humao.rest-client` 插件已停止维护（最后版本 2022 年），本目录已统一迁移到 httpYac 格式。

## 项目索引

| 目录 | 技术栈 | 一句话用途 | 文档 |
|---|---|---|---|
| [ai](ai/) | httpYac `.http` | 混元 / GLM / 星火 / 阿里百炼 / DeepSeek 等大模型对话接口调试样例 | [README](ai/README.md) |
| [baidu](baidu/) | httpYac `.http` | 百度 AI 搜索（千帆 web_search）接口调试样例，供答题 RAG 使用 | [README](baidu/README.md) |
| [bjxd](bjxd/) | httpYac `.http` | 北京现代会员系统：用户 / 文章 / 答题 / 签到 / 任务 / 成绩接口样例 | [README](bjxd/README.md) |
| [oshwhub](oshwhub/) | Python requests | 立创开源平台用户信息、签到接口手动调试脚本 | [README](oshwhub/README.md) |
| [qinglong](qinglong/) | httpYac `.http` | 青龙面板认证、环境变量接口调试样例 | [README](qinglong/README.md) |
| [stock_xueqiu](stock_xueqiu/) | httpYac `.http` | 雪球行情 / K 线 / 分钟线接口样例 | [README](stock_xueqiu/README.md) |
| [wx_pusher](wx_pusher/) | httpYac `.http` | wxPusher 微信推送消息接口样例 | [README](wx_pusher/README.md) |

## 运行方式

### VS Code GUI

安装 `anweber.vscode-httpyac` 插件，打开任一 `.http` 文件，点击请求上方的 **send** 即可。

### CLI（适合脚本 / AI Agent 调用）

```bash
# 执行单个文件里的全部请求
httpyac send api/stock_xueqiu/quotec.http --all -o short

# 只执行指定名称的请求（配合 # @name）
httpyac send api/qinglong/envs.http --name getToken -o short

# 用 --var 临时覆盖 / 提供变量（无需 .env）
httpyac send api/bjxd/getUserInfo.http --var BASE_URL=https://example.com --var BJXD=faketoken -o short

# 常用参数：--all 全部 / -n 按名 / -e 环境 / --json / --junit / -o short|body|response / --insecure
```

完整参数见 `httpyac help send`。CLI 支持 `--json`/`--junit` 输出，便于 AI agent 解析结果。

## 格式说明（与 REST Client 的差异）

- **变量与密钥**：`.env` 中的变量用 `{{变量名}}` 引用（httpyac 默认启用 dotenv，从 `.http` 同目录 / 项目根 / `env/` 目录查找 `.env`）；先复制各目录 `.env.example` 为 `.env` 填入真实值，`.env` 不入库。
- **日期变量**：`{{$datetime 'YYYYMMDD'}}` 沿用原写法，httpyac 兼容。
- **命名请求**：`# @name xxx` 保留。
- **跨请求引用**：REST Client 的 `{{req.response.body.x}}` 改为 `{{req.x}}`，并在使用处加 `# @ref req` 声明依赖（会先执行被引用的请求）。例如：
  ```http
  ### 获取 Token
  # @name getToken
  GET {{BASE_URL}}/open/auth/token

  ### 使用 Token
  # @name getEnvs
  # @ref getToken
  GET {{BASE_URL}}/open/envs
  Authorization: Bearer {{getToken.data.token}}
  ```
- **交互式输入**：原 `# @prompt answer 提示语` 改为普通变量 `{{answer}}`，由调用方提供——CLI 用 `--var answer=C`（AI agent 非交互场景必需），GUI 在 httpyac 变量面板填值。如需弹窗输入也可临时写成 `{{ $input '提示语' }}`。
- **响应样例**：每个 `.http` 文件下方以注释形式附了历史响应内容，便于对照字段。

## 共性说明

- 除 `oshwhub/` 是 Python requests 脚本外，其余目录均为纯 `.http` 样例。
- `.http` 文件本身不含可执行构建产物；所有调用都通过 httpYac GUI 或 CLI 发起。
