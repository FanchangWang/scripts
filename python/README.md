# python

Python 工具 / 脚本 / Web 服务集合，统一使用 [uv](https://docs.astral.sh/uv/) 管理虚拟环境。

## 项目索引

| 目录 | 类型 | 一句话用途 | 文档 |
|---|---|---|---|
| [bjxd_getToken](bjxd_getToken/) | GUI（PyQt6 WebEngine） | 内嵌登录北京现代 H5，取 localStorage token 并复制 | [README](bjxd_getToken/README.md) |
| [ningway_download](ningway_download/) | 命令行脚本 | ningway.com 音视频资源下载工具 | [README](ningway_download/README.md) |
| [process_pause](process_pause/) | GUI（tkinter） | 暂停 / 恢复内存占用最大的微信小程序进程 | [README](process_pause/README.md) |
| [queens_sudoku](queens_sudoku/) | 自动化脚本 | 皇后数独截图识别 + 回溯求解 + 自动点击（PC / 手机） | [README](queens_sudoku/README.md) |
| [scrcpy](scrcpy/) | GUI（PyQt6） | 列出 adb 设备，选机后拉起 scrcpy 投屏 | [README](scrcpy/README.md) |
| [stock_save](stock_save/) | Web 服务（FastAPI） | 雪球分钟线抓取落盘 + Web 图表展示 + wxPusher 通知 | [README](stock_save/README.md) |
| [sublime_crack](sublime_crack/) | 命令行 / GUI | Sublime Text 去未注册弹窗补丁工具 | [README](sublime_crack/README.md) |
| [tinypng](tinypng/) | 命令行脚本 | TinyPNG API 批量压缩 PNG，递归保持目录结构 | [README](tinypng/README.md) |
| [winget_updater](winget_updater/) | GUI（Tkinter） | winget 更新检查，逐条选代理 / 直连 / 跳过策略的 GUI | [README](winget_updater/README.md) |
| [xiangqi-bot](xiangqi-bot/) | Web 服务（FastAPI） | ADB 控手机下中国象棋，附 FastAPI 网页界面 | [README](xiangqi-bot/README.md) |
| [xueqiu_getCookie](xueqiu_getCookie/) | GUI（PyQt6 WebEngine） | 抓取雪球网站登录 Cookie | [README](xueqiu_getCookie/README.md) |

## 共性说明

- **环境管理**：每个项目是独立 uv 工程，先 `uv sync` 同步依赖，再用 `uv run <命令>` 运行；Python 版本以各项目 `.python-version` / `pyproject.toml` 为准。
- **类型划分**：GUI 工具、命令行脚本、Web 服务三类，具体入口命令见各项目 README。
- **敏感信息**：Token / Cookie / API Key 放 `.env` 或本地配置文件，不入库；模板见 `.env.example`。
- 部分项目依赖外部程序（adb、scrcpy.exe、引擎等），需另行安装并加入 PATH，详见各项目 README。
