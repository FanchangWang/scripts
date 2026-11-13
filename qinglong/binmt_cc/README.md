# binmt_cc

MT 论坛（bbs.binmt.cc）自动签到、查看积分的青龙定时脚本；纯 Python 解 `acw_sc__v2` WAF 盾，登录遇验证码时用 ddddocr 识别。

## 环境要求

- Python 3，依赖见 `requirements.txt`：`requests`、`beautifulsoup4`、`python-dotenv`、`ddddocr`。
- 青龙面板环境变量：

| 变量 | 说明 |
|---|---|
| `BINMT_CC_USERNAME` | bbs.binmt.cc 用户名 |
| `BINMT_CC_PASSWORD` | bbs.binmt.cc 密码 |

## 快速开始

1. 在青龙面板安装 `requirements.txt` 依赖。
2. 配置上述环境变量。
3. 新建定时任务，运行脚本 `binmt_cc.py`，建议 cron：`12 3 * * *`。

本地调试：

```bash
pip install -r requirements.txt
python binmt_cc.py
```

## 用法 / 参数

- 无命令行参数；账号密码全部来自环境变量。
- 通知：青龙环境下调用 `QLAPI.notify` 推送日志；本地运行直接打印到控制台。

## 功能 / 行为说明

- **WAF 盾求解**：前置一道 JS 反爬挑战页，脚本用纯 Python 完成 `arg1` 的 permute / xor，算出 `acw_sc__v2` Cookie 后继续请求。
- **登录**：提交账号密码；触发验证码时用 ddddocr 识别并自动重试。未安装 ddddocr 时仅记录提示，无法过验证码。
- **签到 / 积分**：登录后执行签到并查询积分、签到排名等信息，结果写入日志并推送通知。
- 脚本禁用了 SSL 警告；登出后清理会话。

## 开发

- 入口：`binmt_cc.py`；WAF 求解函数 `solve_acw_sc__v2` / `_acw_permute` / `_acw_xor`。
- 论坛接口、表单字段如需调整，对照源码中的登录 / 签到流程修改。
