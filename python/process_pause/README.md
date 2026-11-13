# process_pause

微信小程序暂停工具：tkinter 界面暂停 / 恢复内存占用最大的 `WeChatAppEx.exe` 进程。

## 环境要求

- Python ≥ 3.8（Windows）
- 依赖：`psutil>=7.2.2`
- 打包（可选）：dev 依赖 `pyinstaller>=6.22.3`
- 使用 uv 管理虚拟环境

## 快速开始

```bash
uv sync
uv run main.py
```

## 用法 / 参数

1. 点「查询进程」：列出当前所有 `WeChatAppEx.exe`，按内存 RSS 排序取最大的一个，显示其 PID。
2. 点「暂停进程」：`psutil.Process(pid).suspend()` 挂起该进程，按钮变为「恢复进程」。
3. 点「恢复进程」：`resume()` 唤醒进程。

无命令行参数；窗口置顶、隐藏最小化按钮。

## 功能 / 行为说明

- 目标进程固定为 `WeChatAppEx.exe`（微信小程序进程），按内存占用最大者选取。
- 未找到进程时弹窗提示「未找到运行中的 WeChatAppEx.exe 进程」。
- 暂停 / 恢复失败（权限不足）时弹窗提示，建议以管理员身份运行。
- 启动时设置高 DPI 感知（`SetProcessDpiAwareness`）。

## 开发

### 打包单文件 exe

```bash
# Windows，PowerShell / cmd
.\build.bat
```

脚本流程：`uv sync` → 添加 pyinstaller dev 依赖 → `uv run PyInstaller --onefile --windowed main.py`，产物为 `dist/run.exe`，并清理 `build/` 与 `.spec` 临时文件。
