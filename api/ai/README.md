# ai

多家大模型对话接口的 REST Client 调试样例，用于对比 / 验证各家模型的问答（含北京现代答题提示词样例）。

## 环境要求

- 支持 REST Client 的 IDE（VS Code REST Client 插件 / JetBrains HTTP Client）。
- 对应厂商的 API Key，在同目录 `.env` 中配置（模板见 `.env.example`）。

## 快速开始

1. 复制 `.env.example` 为 `.env`，填入各厂商 API Key。
2. 用 IDE 打开任一 `.http` 文件，点击请求上方的发送按钮即可调用。

## 接口清单

| 文件 | 厂商 | 模型 | 端点 | 环境变量 |
|---|---|---|---|---|
| `chat.hunyuan-lite.http` | 腾讯混元 | hunyuan-lite | `https://api.hunyuan.cloud.tencent.com/v1/chat/completions` | `HUNYUAN_API_KEY` |
| `chat.hunyuan-turbo.http` | 腾讯混元 | hunyuan-turbo | 同上 | `HUNYUAN_API_KEY` |
| `chat.glm-4-flash-250414.http` | 智谱 AI | glm-4-flash-250414 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | `GLM_API_KEY` |
| `chat.glm-4.5-flash.http` | 智谱 AI | glm-4.5-flash | 同上 | `GLM_API_KEY` |
| `chat.glm-4.6v-flash.http` | 智谱 AI | glm-4.6v-flash | 同上 | `GLM_API_KEY` |
| `chat.glm-4.7.http` | 智谱 AI | glm-4.7 | 同上 | `GLM_API_KEY` |
| `chat.spark-lite.http` | 讯飞星火 | spark-lite | `https://spark-api-open.xf-yun.com/v1/chat/completions` | `SPARK_API_KEY` |
| `chat.aliyun-deepseek-r1-qwen-1.5b.http` | 阿里百炼 | deepseek-r1-qwen-1.5b | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | `ALIYUN_API_KEY` |

## 功能 / 行为说明

- 均为 OpenAI 兼容的 `/chat/completions` POST 请求，`Authorization: Bearer <Key>`。
- 请求体中的 system / user 消息为答题提示词与题目样例，可按需替换；文件下方注释附了历史响应样例。
- `.env` 中的 Key 不入库，请勿提交真实密钥。
