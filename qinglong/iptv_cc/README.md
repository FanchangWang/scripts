# iptv_cc

iptv.cc 自动签到、浏览 / 回复帖子、查看积分的青龙定时脚本。

## 环境要求

- Python 3，依赖见 `requirements.txt`：`requests`、`beautifulsoup4`、`python-dotenv`。
- 青龙面板环境变量：

| 变量 | 说明 |
|---|---|
| `IPTV_CC_USERNAME` | iptv.cc 用户名 |
| `IPTV_CC_PASSWORD` | iptv.cc 密码 |

## 快速开始

1. 安装依赖：`pip install -r requirements.txt`。
2. 在青龙面板配置上述环境变量。
3. 新建定时任务运行 `iptv_cc.py`，建议 cron：`48 3 * * *`。

本地调试：

```bash
pip install -r requirements.txt
python iptv_cc.py
```

## 用法 / 参数

- 无命令行参数；账号密码来自环境变量。
- 通知：青龙下调用 `QLAPI.notify`；本地运行直接打印日志。

## 功能 / 行为说明

主流程（`run`）：

1. 登录账号；登录成功后先查一次积分。
2. 若网站处于整改 / 维护状态，跳过后续签到回帖并在标题标注。
3. **签到**：提交签到表单，识别「签到成功 / 今日已签」等结果。
4. **回复帖子**：当天尚未回复过时，浏览帖子列表并进入帖子完成回复；已回复则跳过。
5. 再次查询积分，登出并推送汇总通知。

- 脚本禁用了 SSL 警告；会话结束后调用 `logout` 清理。

## 开发

- 入口：`iptv_cc.py`；主要方法 `login` / `sign` / `view_thread` / `view_thread_content` / `check_score_info` / `get_score_info`。
- 帖子列表、回帖表单字段如需调整，对照源码与站点页面修改。
