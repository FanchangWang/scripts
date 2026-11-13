# 自用脚本

个人自用小项目聚合仓库，按语言 / 用途分目录收纳。

## 分组索引

| 分组 | 说明 | 项目数 | 文档 |
|---|---|---|---|
| [android](android/) | Android（Kotlin / Gradle）工程 | 1 | [README](android/README.md) |
| [api](api/) | 第三方接口调试集合（REST Client `.http` + 少量脚本） | 6 | [README](api/README.md) |
| [nodejs](nodejs/) | Node.js 原生 ESM 项目 | 1 | [README](nodejs/README.md) |
| [python](python/) | Python 工具 / 脚本 / Web 服务（uv 管理） | 11 | [README](python/README.md) |
| [qinglong](qinglong/) | 青龙面板定时脚本 | 5 | [README](qinglong/README.md) |

## 项目索引

### [android](android/)

| 目录 | 技术栈 | 一句话用途 | 文档 |
|---|---|---|---|
| [xueqiuCookie](android/xueqiuCookie/) | Kotlin / Android / WebView | WebView 登录雪球后读取、复制并导出登录 Cookie 的 App | [README](android/xueqiuCookie/README.md) |

### [api](api/)

| 目录 | 技术栈 | 一句话用途 | 文档 |
|---|---|---|---|
| [ai](api/ai/) | REST Client `.http` | 混元 / GLM / 星火 / 阿里百炼 / DeepSeek 等大模型对话接口调试样例 | [README](api/ai/README.md) |
| [bjxd](api/bjxd/) | REST Client `.http` | 北京现代会员系统：用户 / 文章 / 答题 / 签到 / 任务 / 成绩接口样例 | [README](api/bjxd/README.md) |
| [oshwhub](api/oshwhub/) | Python requests | 立创开源平台用户信息、签到接口手动调试脚本 | [README](api/oshwhub/README.md) |
| [qinglong](api/qinglong/) | REST Client `.http` | 青龙面板认证、环境变量接口调试样例 | [README](api/qinglong/README.md) |
| [stock_xueqiu](api/stock_xueqiu/) | REST Client `.http` | 雪球行情 / K 线 / 分钟线接口样例 | [README](api/stock_xueqiu/README.md) |
| [wx_pusher](api/wx_pusher/) | REST Client `.http` | wxPusher 微信推送消息接口样例 | [README](api/wx_pusher/README.md) |

### [nodejs](nodejs/)

| 目录 | 技术栈 | 一句话用途 | 文档 |
|---|---|---|---|
| [daily-calendar-puzzle](nodejs/daily-calendar-puzzle/) | Node 原生 ESM | 日历拼版同源求解器 + 求解 / 游玩双网页 + CLI | [README](nodejs/daily-calendar-puzzle/README.md) |

### [python](python/)

| 目录 | 技术栈 | 一句话用途 | 文档 |
|---|---|---|---|
| [bjxd_getToken](python/bjxd_getToken/) | PyQt6 / WebEngine | 内嵌登录北京现代 H5，取 localStorage token 并复制 | [README](python/bjxd_getToken/README.md) |
| [ningway_download](python/ningway_download/) | Python | ningway.com 音视频资源下载工具 | [README](python/ningway_download/README.md) |
| [process_pause](python/process_pause/) | tkinter / psutil | 暂停 / 恢复内存占用最大的微信小程序进程 | [README](python/process_pause/README.md) |
| [queens_sudoku](python/queens_sudoku/) | Python / uv | 皇后数独截图识别 + 回溯求解 + 自动点击（PC / 手机） | [README](python/queens_sudoku/README.md) |
| [scrcpy](python/scrcpy/) | PyQt6 | 列出 adb 设备，选机后拉起 scrcpy 投屏 | [README](python/scrcpy/README.md) |
| [stock_save](python/stock_save/) | FastAPI / uvicorn / httpx | 雪球分钟线抓取落盘 + Web 图表展示 + wxPusher 通知 | [README](python/stock_save/README.md) |
| [sublime_crack](python/sublime_crack/) | Python | Sublime Text 去未注册弹窗补丁工具 | [README](python/sublime_crack/README.md) |
| [tinypng](python/tinypng/) | Python | TinyPNG API 批量压缩 PNG，递归保持目录结构 | [README](python/tinypng/README.md) |
| [winget_updater](python/winget_updater/) | Python / Tkinter | winget 更新检查，逐条选代理 / 直连 / 跳过策略的 GUI | [README](python/winget_updater/README.md) |
| [xiangqi-bot](python/xiangqi-bot/) | Python / FastAPI | ADB 控手机下中国象棋，附 FastAPI 网页界面 | [README](python/xiangqi-bot/README.md) |
| [xueqiu_getCookie](python/xueqiu_getCookie/) | PyQt6 / WebEngine | 抓取雪球网站登录 Cookie | [README](python/xueqiu_getCookie/README.md) |

### [qinglong](qinglong/)

| 目录 | 技术栈 | 一句话用途 | 文档 |
|---|---|---|---|
| [binmt_cc](qinglong/binmt_cc/) | Python / requests | MT 论坛签到、查积分，纯 Python 解 acw_sc__v2 WAF 盾 | [README](qinglong/binmt_cc/README.md) |
| [bjxd](qinglong/bjxd/) | Python / requests | 北京现代 APP 签到 / 浏览文章 / 答题自动任务（多账号 + 可选 AI 答题） | [README](qinglong/bjxd/README.md) |
| [iptv_cc](qinglong/iptv_cc/) | Python / requests | iptv.cc 签到、自动回帖、查积分 | [README](qinglong/iptv_cc/README.md) |
| [oshwhub](qinglong/oshwhub/) | Python / requests + PyQt5 | 开源平台签到 / 7 天与月度好礼；另附抓 Cookie 的 GUI 工具 | [README](qinglong/oshwhub/README.md) |
| [stock](qinglong/stock/) | Python / requests | 股票实时监控，检查交易事件并计算持仓盈亏 | [README](qinglong/stock/README.md) |

## 约定

- 密钥、Token、Cookie 等敏感信息一律放 `.env` 或本地配置文件，不入库；模板见各项目 `.env.example`。
- Python 项目统一使用 [uv](https://docs.astral.sh/uv/) 管理虚拟环境。
- 各项目自身的环境要求、运行方式与参数说明，见其目录下 `README.md`。
