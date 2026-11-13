# bjxd

北京现代 APP 自动任务青龙脚本：自动签到、浏览文章、每日答题，支持多账号与可选 AI 答题。

## 环境要求

- Python 3，依赖见 `requirements.txt`：`requests`、`python-dotenv`。
- 青龙面板环境变量：

| 变量 | 必填 | 说明 |
|---|---|---|
| `BJXD` | 二选一 | 用户 token，多个账号用英文逗号分隔 |
| `BJXD1` / `BJXD2` / `BJXD3` / `BJXD4` | 二选一 | 每个账号一个变量，逐个读取 |
| `BJXD_ANSWER` | 可选 | 预设答案（`A` / `B` / `C` / `D`），配置后答题直接使用 |
| `AI_API_KEY` | 可选 | 通用 AI API Key |
| `AI_REQUEST_URL` | 可选 | 通用 AI 请求地址 |
| `AI_MODEL` | 可选 | 通用 AI 模型名称 |
| `AI_REQUEST_PARAMS` | 可选 | 通用 AI 请求参数，JSON 格式字符串 |

> 旧变量 `HUNYUAN_API_KEY`、`GLM_API_KEY` 已废弃，仅做兼容保留，请改用 `AI_XXXX` 通用配置。

## 快速开始

1. 安装依赖：`pip install -r requirements.txt`。
2. 在青龙面板配置 token（`BJXD` 逗号分隔或 `BJXD1..4`）及可选答题配置。
3. 新建定时任务运行 `bjxd.py`，建议 cron：`25 6 * * *`。

本地调试：

```bash
pip install -r requirements.txt
python bjxd.py
```

## 用法 / 参数

- 无命令行参数；账号与答题配置全部来自环境变量。
- 答题答案优先级：**预设答案 `BJXD_ANSWER` → 通用 AI 答题 → 无可用答案**。
- AI 请求为 OpenAI 兼容的 `chat/completions`，从返回内容中提取 A/B/C/D；`AI_REQUEST_PARAMS` 以 JSON 字符串并入请求体。
- 通知：青龙下调用 `QLAPI.notify`；本地运行直接打印日志。

## 功能 / 行为说明

- **签到**：调用签到接口完成当日签到。
- **浏览文章**：拉取文章列表，逐篇打开详情并提交打分 / 浏览记录。
- **每日答题**：拉取当日题目，按上述优先级取答案后提交；记录错误答案集合，避免重复踩坑。
- 多账号依次执行，日志区分各账号结果。

## 开发

- 入口：`bjxd.py`；接口常量见文件顶部 `API_*`。
- 新增 AI 厂商时，填入兼容的 `AI_REQUEST_URL` / `AI_MODEL` / `AI_REQUEST_PARAMS` 即可，无需改代码。
