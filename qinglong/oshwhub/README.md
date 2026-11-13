# oshwhub

嘉立创开源硬件平台（oshwhub.com）自动任务青龙脚本：签到、领取 7 天好礼与月度好礼；另附一个 PyQt5 WebEngine 抓 Cookie 的 GUI 工具。

> 手动调试用户信息 / 签到接口的 Python 脚本见仓库 `api/oshwhub/`，二者分开维护。

## 环境要求

- 定时脚本：Python 3 + `requests`（青龙环境通常自带）。
- GUI 抓 Cookie 工具：需另装 `PyQt5`、`PyQt5-WebEngine`。
- 青龙面板环境变量（每个账号一个变量）：

| 变量 | 说明 |
|---|---|
| `oshwhub1` / `oshwhub2` / `oshwhub3` / `oshwhub4` | 登录 Cookie，格式 `oshwhub_csrf=xxx; oshwhub_session=xxx` |

## 快速开始

### 定时签到（青龙）

1. 用下方 GUI 工具或浏览器登录平台，复制完整 Cookie。
2. 在青龙面板配置 `oshwhub1..4`（按需增减）。
3. 新建定时任务运行 `oshwhub.py`，建议 cron：`10 5 * * *`。

### 本地调试脚本

```bash
python oshwhub.py
```

### 抓取 Cookie（GUI）

```bash
python oshwhub_get_cookie_gui.py
```

## 用法 / 参数

- `oshwhub.py` 无命令行参数，逐个读取 `oshwhub1..4` 中的 Cookie 循环执行。
- 通知：青龙下调用 `QLAPI.notify`；本地运行直接打印日志。
- GUI 工具：内嵌浏览器登录后，点按钮抓取 `oshwhub_session`、`oshwhub_csrf` 并拼接复制，粘贴到上面的环境变量即可。

## 功能 / 行为说明

- **用户信息**：校验 Cookie 是否有效，失效账号记录提示并跳过。
- **签到**：`POST /api/users/signIn`。
- **签到档案 / 好礼**：`GET /api/users/getSignInProfile`，读取今日是否已签、本周 / 本月签到天数、7 天好礼与月度好礼领取状态，并按状态领取对应好礼。
- 多账号依次执行，日志区分各账号结果。

## 开发

- 定时入口：`oshwhub.py`；接口常量见文件顶部 `API_*`。
- GUI 入口：`oshwhub_get_cookie_gui.py`（`CookieViewer`）。
- `requirements.txt` 未列依赖：脚本依赖 `requests`；GUI 依赖 PyQt5 系列，本地运行前请自行安装。
