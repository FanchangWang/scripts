# bjxd

北京现代 APP 自动任务脚本：自动签到、浏览文章、每日答题，支持多账号、AI 答题与**联网搜索辅助答题**。

## 环境要求

- Python 3.9+，依赖：`requests`、`python-dotenv`（无新增第三方依赖，联网搜索用 `requests` 直调百度官方 API）
- **两种运行形态兼容**：
  - 青龙面板：`pip install -r requirements.txt` 后由青龙直接 `python bjxd.py`
  - 本地开发：uv 管理的独立工程，`uv run python bjxd.py` 或 `uv run bjxd`

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `BJXD` | 二选一 | 用户 token，多个账号用英文逗号分隔 |
| `BJXD1` / `BJXD2` / `BJXD3` / `BJXD4` | 二选一 | 每个账号一个变量，逐个读取 |
| `BJXD_ANSWER` | 可选 | 预设答案（`A` / `B` / `C` / `D`），配置后答题直接使用 |
| `AI_API_KEY` | 可选 | 通用 AI API Key |
| `AI_REQUEST_URL` | 可选 | 通用 AI 请求地址（OpenAI 兼容 `chat/completions`） |
| `AI_MODEL` | 可选 | 通用 AI 模型名称，如 `glm-4.5-flash` |
| `AI_REQUEST_PARAMS` | 可选 | 通用 AI 请求参数，JSON 格式字符串 |
| `SEARCH_API_KEY` | 可选 | 百度 AI 搜索 API Key（`bce-v3/ALTAK-...` 格式），不填则不启用联网搜索 |
| `SEARCH_RESULT_COUNT` | 可选 | 搜索结果条数，默认 `5` |
| `SEARCH_TIMEOUT` | 可选 | 搜索超时秒数，默认 `30` |

> 旧变量 `HUNYUAN_API_KEY`、`GLM_API_KEY` 已废弃，仅做兼容保留，请改用 `AI_XXXX` 通用配置。

## 快速开始

本地（uv）：

```bash
uv sync
cp .env.example .env   # 填入 token 与 AI / 搜索配置
uv run python bjxd.py
# 或
uv run bjxd
```

青龙面板：

1. `pip install -r requirements.txt`（依赖与 `pyproject.toml` 保持一致）。
2. 配置 `BJXD` / `BJXD1..4` 及可选的答题配置。
3. 新建定时任务运行 `bjxd.py`，建议 cron：`25 6 * * *`。

## 用法 / 参数

- 无命令行参数；账号与答题配置全部来自环境变量。
- 答题答案优先级：**历史正确答案 → 预设答案 → 联网搜索 + AI 答题 → 随机答案**。
- 联网搜索流程：取题干（去掉选项）作为关键词 → 调用百度 AI 搜索 → 取 `references` 的标题/摘要 → 拼成「资料 + 题目」提交给 AI。
- **同一题干只搜一次**：结果缓存在实例内，所有账号复用第一份资料，不会重复请求（一个账号一天一次，远在免费额度内）。
- 搜索失败（网络异常、Key 无效、免费额度用完返回 `QUOTA_USER_DAILY_FREE`）时**自动降级**为「只用题目问 AI」，不影响签到等其他任务。
- AI 请求为 OpenAI 兼容的 `chat/completions`，从返回内容中提取 A/B/C/D（支持「正确答案是 C」这类表述，并排除已知错误答案）；`AI_REQUEST_PARAMS` 以 JSON 字符串并入请求体。
- 通知：青龙下调用 `QLAPI.notify`；本地运行直接打印日志。

## 功能 / 行为说明

- **签到**：多次尝试取最高积分后提交。
- **浏览文章**：拉取文章列表，随机打开最多 3 篇详情并提交打分。
- **每日答题**：拉取当日题目，按上述优先级取答案后提交；记录错误答案集合，避免重复踩坑。
- **联网搜索辅助**：走百度 AI 搜索（千帆 `v2/ai_search/web_search`），国内直连；每月免费 1500 次（约每天 50 次），本脚本一天一次用不完，等于长期免费；需在百度智能云实名认证后创建 `bce-v3/...` 格式的 API Key 填入 `SEARCH_API_KEY`。不填则自动关闭。
- 多账号依次执行，日志区分各账号结果。

## 开发

- 入口：`bjxd.py`（根目录单文件，青龙与 uv 两种形态共用同一份代码）；`main()` 供 `uv run bjxd` 的 console script 使用。
- `pyproject.toml` 声明依赖与 `bjxd` 命令；`requirements.txt` 供青龙 pip 安装，两者依赖需保持同步。
- `uv.lock` 按仓库约定不入库；`uv run` 会自动解析依赖。
- 接口常量见文件顶部 `API_*`；接口调试样例见 `api/bjxd/`。
- 新增 AI 厂商时，填入兼容的 `AI_REQUEST_URL` / `AI_MODEL` / `AI_REQUEST_PARAMS` 即可，无需改代码。
