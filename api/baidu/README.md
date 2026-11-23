# baidu

百度 AI 搜索（千帆 `web_search`）接口的 httpYac 调试样例。该接口供 `qinglong/bjxd` 的联网搜索辅助答题使用，单独记录在此便于调试与复用。

## 环境要求

- httpYac（VS Code 插件 `anweber.vscode-httpyac` 或 CLI `httpyac`）。
- 百度智能云实名认证的 API Key，在同目录 `.env` 配置（模板见 `.env.example`）。

## 快速开始

1. 复制 `.env.example` 为 `.env`，填入 `BAIDU_API_KEY`（形如 `bce-v3/ALTAK-xxxx/xxxx`）。
2. GUI：用 httpYac 打开 `web_search.http`，点击 send。
3. CLI：

```bash
httpyac send api/baidu/web_search.http --all -o body
# 或用 --var 临时提供 Key（无需 .env）
httpyac send api/baidu/web_search.http --all --var BAIDU_API_KEY=bce-v3/xxx -o short
```

## 接口说明

- **端点**：`POST https://qianfan.baidubce.com/v2/ai_search/web_search`
- **鉴权**：`Authorization: Bearer <bce-v3/ALTAK-...>`
- **请求体关键字段**：
  - `messages[0].content`：搜索关键词（官方建议不超过 70 字符）
  - `search_source`：`baidu_search_v2`
  - `resource_type_filter`：`[{"type": "web", "top_k": N}]` 控制返回条数
- **返回**：`references[]`，每条含 `title` / `url` / `content`（摘要正文）/ `website`（站点名）/ `date` / `rerank_score` 等。
- **费用**：每月免费 1500 次（按天发放约每天 50 次），超出需开通后付费。错误码 `QUOTA_USER_DAILY_FREE` 表示免费额度用完。

## 备注

- 这是官方 API、国内直连，无需代理；结果结构化，适合喂给大模型做检索增强（RAG）。
- `.env` 中的 Key 不入库，请勿提交真实密钥。
- 申请 Key：百度智能云控制台 → AI 搜索 → API Key 管理（需实名认证）。
