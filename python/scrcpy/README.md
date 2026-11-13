# scrcpy

scrcpy 投屏 GUI 启动器：列出 adb 已连接设备，选择后调用 `scrcpy.exe` 投屏。

## 环境要求

- Python ≥ 3.12，依赖 `PyQt6>=6.11.0`
- 外部命令需在系统 PATH 中：
  - `adb`（Android 调试桥）
  - `scrcpy.exe`
- 使用 uv 管理虚拟环境

## 快速开始

```bash
uv sync
uv run main.py
```

命令行额外参数会原样透传给 scrcpy，例如：

```bash
uv run main.py --turn-screen-off
```

## 用法 / 参数

启动后的流程：

1. 执行 `adb devices -l`，列出状态为 `device` 的在线设备（排除 `offline`）。
2. 设备数量：
   - **1 台**：直接对该设备启动 scrcpy。
   - **多台**：弹出设备选择对话框，选中后启动。
   - **0 台**：询问是否 `adb connect 192.168.31.60:5555` 尝试无线连接（该 IP 为源码中硬编码，按需修改，**待核对**）。
3. 启动前弹窗询问「是否保持屏幕关闭？」，是则追加 `--turn-screen-off`。

固定启动参数：`scrcpy.exe --stay-awake --audio-dup --window-height=1400 [-s <设备ID>] [透传参数]`。

## 功能 / 行为说明

- 找不到 `adb` / `scrcpy.exe` 时弹窗提示并退出。
- scrcpy 以无控制台窗口方式后台拉起（`CREATE_NO_WINDOW`）。
- 界面中文字体设为 SimHei。

## 开发

- 入口：`main.py`；设备选择对话框 `DeviceSelector`。
- 无线连接默认地址在 `main()` 中硬编码，如需更换修改 `connect_to_device` 调用处。
