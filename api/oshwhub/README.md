# oshwhub

立创开源硬件平台（oshwhub.com）用户信息 / 签到接口的 Python requests 手动调试脚本。

> 青龙定时签到任务见仓库 `qinglong/oshwhub/`，二者分开维护。

## 环境要求

- Python 3，依赖 `requests`。
- 环境变量 `oshwhub1`：登录后的 `oshwhub_session` Cookie 值。

## 快速开始

```bash
# 先在环境变量中设置 cookie（PowerShell 示例）
$env:oshwhub1 = "<oshwhub_session 值>"

python api_oshwhub.py
```

## 用法 / 参数

- 脚本默认请求「获取用户信息」：`GET https://oshwhub.com/api/users`，请求头 `Cookie: oshwhub_session=<cookie>`。
- 其余接口（提交签到、签到信息、签到记录）在源码中以注释形式给出，按需放开注释运行。

## 功能 / 行为说明

- 已开放：用户信息查询，打印响应 JSON。
- 注释样例：
  - `POST /api/users/signIn`：提交签到，`result: true` 表示签到成功。
  - `GET /api/users/getSignInProfile`：签到信息（今日是否已签、本周 / 本月天数、积分等）。
  - `GET /api/users/signInRecord?startTime=&endTime=`：签到记录（GMT 时间，脚本示例转东八区）。
- Cookie 失效 / 错误时返回 `code: 401`。
