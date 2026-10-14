# winget_updater

检查 winget 可用更新，GUI 逐条选择更新策略（代理/直连/跳过）后执行更新。Windows 11 个人自用工具。

## 用法

```bat
cd C:\Users\guyue\code\scripts\python\winget_updater
uv run winget-updater
```

或双击 `run.bat`，或 `uv run python -m winget_updater`。

## 命令行参数

| 参数 | 说明 |
|---|---|
| `--show-skipped`, `-s` | 强制显示配置中记录为「跳过本版本 / 永久跳过」的条目 |
| `--no-proxy` | 检查更新时不使用代理（默认使用代理 `http://192.168.31.50:7890`） |
| `--config PATH` | 指定配置文件路径（默认：项目根目录 `config.json`） |

## 行为说明

1. 检查更新：`winget upgrade --proxy http://192.168.31.50:7890`；无更新时打印提示并退出。
2. 有更新时弹出 GUI，每行右侧 5 个选项列，点击单元格即选中：
   - **更新(使用代理)**：默认全部选中。执行 `winget upgrade --id <pkg> --silent ... --proxy <uri>`
   - **更新(不用代理)**：同上但不带代理（`--no-proxy`）
   - **本次不更新**：不记录，下次运行仍会提示
   - **跳过本版本**：记录跳过的版本号，该版本一直不提示；发布新版本后自动重新提示
   - **永久跳过**：记录后不再提示（除非 `--show-skipped`）
3. 除「本次不更新」外的 4 种选项都会写入 `config.json`；更新类选项同时作为该包下次运行时的默认动作。
4. 更新在工作线程中逐条执行，状态列实时刷新；「停止」= 当前包更新完成后不再继续。
5. 「安装详情」面板（表格下方）以终端风格实时显示每个包的 winget 输出（stdout/stderr 合并逐行流出，错误行标红，长行自动换行），开始更新时自动清空。
6. 下载进度：winget 的下载进度（已下载大小/总大小/进度条）用 `\r` 覆盖同一行输出，工具按帧实时刷新到详情面板（青色行，原地覆盖，与终端效果一致）；进度刷新节奏与 winget 输出节奏相同（按 `\n` 成批到达）。
7. Windows 高 DPI 适配：启动时设置进程级 Per-Monitor V2 awareness（自动降级 Per-Monitor → System），创建 Tk 后按 dpi/96 显式设置 tk scaling，并同步放大命名字体、Treeview 行高、Treeview 列宽与窗口 geometry——控件、文字、列宽、窗口四者等比，高分屏下不模糊且大小与标准 Win11 应用一致；窗口默认 1560x780（逻辑）居中，完整显示全部 11 列。
8. 系统主题跟随：启动时读取注册表 `Personalize\AppsUseLightTheme` 判断 Windows 深浅色，应用对应的 ttk 主题（clam 定制的 light/dark 两套配色，覆盖按钮、表格、表头、滚动条、LabelFrame；详情面板 Text 为 tk 原生控件，配色单独同步）；标题栏经 `DwmSetWindowAttribute` 切换到沉浸式深色并按主题着色（caption/border/text color，Win11 22000+，失败静默降级）；之后每秒轮询注册表，系统切换主题后 GUI（含标题栏）自动跟随。消息框等系统对话框由 Windows 自行渲染，天然跟随。
9. machine scope 的包（如 Visual Studio）非提权运行会更新失败，GUI 汇总提示后可以管理员身份重跑。

## 配置文件（config.json）

```json
{
  "version": 1,
  "packages": {
    "Tencent.WeType": {
      "action": "skip_version",
      "version": "3.0.0.32",
      "updated_at": "2026-10-14T19:30:00"
    }
  }
}
```

## 开发

```bash
uv sync          # 创建 .venv（零第三方依赖）
uv run ruff check .
uv run ruff format .
uv run ty check src
uv run python -m unittest discover -s tests -v
```
