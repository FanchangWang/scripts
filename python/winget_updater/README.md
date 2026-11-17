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
| `--show-skipped`, `-s` | 强制显示配置中记录为「跳过本版本 / 永久跳过」的条目（GUI 里也有同名开关，见下） |
| `--no-proxy` | 检查更新时不使用代理（默认使用代理 `http://192.168.31.50:7890`） |
| `--exclude-unknown` | 检查时**不**包含「本地版本无法确定（Unknown）」的包（默认包含，即默认向 winget 传 `--include-unknown`），等价于 GUI 的「检查更新（不含未知版本）」 |
| `--config PATH` | 指定配置文件路径（默认：项目根目录 `config.json`） |

## 行为说明

1. 检查更新：`winget upgrade --include-unknown --proxy http://192.168.31.50:7890`（默认就带 `--include-unknown`，`--exclude-unknown` 可关闭）；无更新时打印提示并退出。
2. 有更新时弹出 GUI，每行右侧 5 个选项列，点击单元格即选中：
   - **更新(使用代理)**：默认全部选中。执行 `winget upgrade --id <pkg> --silent --include-unknown ... --proxy <uri>`——升级阶段也固定带 `--include-unknown`：本地版本号无法确定的包（如 `EPSON.EpsonScan2`）不带它会被 winget 直接拒绝（`无法确定此包的版本号`，exit `0x8A15002B`）
   - **更新(不用代理)**：同上但不带代理（`--no-proxy`）
   - **本次不更新**：不记录，下次运行仍会提示
   - **跳过本版本**：记录跳过的版本号，该版本一直不提示；发布新版本后自动重新提示
   - **永久跳过**：记录后不再提示（除非 `--show-skipped`）
3. 左下角有两个检查按钮，在窗口内重新检查并刷新表格（后台线程执行，结果按当前代理 / `--show-skipped` / 未知版本口径过滤；检查期间动作按钮禁用）：
   - **检查更新**：默认口径，含 `--include-unknown`（把「本地版本无法确定」的包一并列出），也沿用启动时是否 `--exclude-unknown`
   - **检查更新（不含未知版本）**：显式不带 `--include-unknown`，只列出版本可确定的包
   - 可靠性：`winget upgrade` 带 120 秒子进程超时；界面另有 150 秒看门狗，检查线程若仍未返回会自动复位按钮并提示，可直接再点，不会永久停在「正在检查…」；更新跑完（含失败收尾）后轮询继续，随时可以再点检查
4. 左下角「显示已跳过项（N 项）」开关（等价于命令行 `--show-skipped`）：`N` 是 `config.json` 里记录为「跳过本版本 / 永久跳过」的条目数，启动时无完整结果则只显示「显示已跳过项」。取消勾选时隐藏这些条目，勾选后立刻把它们显示出来；切换走本地重过滤（检查结果已在内存里），不会重跑 winget。显示出来的跳过行有明确的视觉区分：**排在表格最后**，且行底色比正常行深一档、文字转为弱化灰（深浅主题各用对应配色，主题切换时同步刷新），不必逐条比对哪几行是后加进来的。跳过行的当前操作仍保持「跳过本版本 / 永久跳过」——不会被改成「更新(使用代理)」，点「开始更新」不会装它们；只有「跳过本版本」记录的版本已过期（出现新版本）时才回到「更新」重新提示。
5. 点击选项列即写入 `config.json`（不必等到点「开始更新」，选完直接关窗口也不会丢选择）；更新类选项同时作为该包下次运行时的默认动作。更新成功后**不再清除**这条记录——更新选项是对「这个包怎么更新」的偏好，留着下次启动才能记住「不用代理」（例如 `ByteDance.Trae.CN`）；版本变化是否仍适用由跳过判定自己判断。
6. 「全部默认(使用代理)」按钮改为按 `config.json` 记录恢复各包的默认动作（无记录才是「更新(使用代理)」）：跳过类记录保持跳过，避免一键把永久跳过的包重新勾成更新。
7. 更新在工作线程中逐条执行，状态列实时刷新；「停止」= 当前包更新完成后不再继续。
8. 「安装详情」面板（表格下方）以终端风格实时显示每个包的 winget 输出（stdout/stderr 合并逐行流出，错误行标红，长行自动换行），开始更新时自动清空。
9. 下载进度：winget 的下载进度（已下载大小/总大小/进度条）用 `\r` 覆盖同一行输出，工具按帧实时刷新到详情面板（青色行，原地覆盖，与终端效果一致）；进度刷新节奏与 winget 输出节奏相同（按 `\n` 成批到达）。
10. Windows 高 DPI 适配：启动时设置进程级 Per-Monitor V2 awareness（自动降级 Per-Monitor → System），创建 Tk 后按 dpi/96 显式设置 tk scaling，并同步放大命名字体、Treeview 行高、Treeview 列宽与窗口 geometry——控件、文字、列宽、窗口四者等比，高分屏下不模糊且大小与标准 Win11 应用一致；窗口默认 1560x780（逻辑）居中，完整显示全部 11 列。
11. 系统主题跟随：启动时读取注册表 `Personalize\AppsUseLightTheme` 判断 Windows 深浅色，应用对应的 ttk 主题（clam 定制的 light/dark 两套配色，覆盖按钮、表格、表头、滚动条、LabelFrame；详情面板 Text 为 tk 原生控件，配色单独同步）；标题栏经 `DwmSetWindowAttribute` 切换到沉浸式深色并按主题着色（caption/border/text color，Win11 22000+，失败静默降级）；之后每秒轮询注册表，系统切换主题后 GUI（含标题栏）自动跟随。消息框等系统对话框由 Windows 自行渲染，天然跟随。
12. machine scope 的包（如 Visual Studio）非提权运行会更新失败，GUI 汇总提示后可以管理员身份重跑。

## 解析说明

`winget upgrade` 重定向到文件时中文列按显示宽度补垫，各列字符串偏移随行变化，无法定宽切列；因此按包 ID 锚定解析：ID 为无空白点分串（`Publisher.Product`），名称取其左侧文本，右侧三个 token 为版本/可用/源（版本为「无法确定」时呈 `< 1.2.3` 两 token）。ID 字符集含 `+`——winget 源里真实存在 `Notepad++.Notepad++`、`Microsoft.VCRedist.2015+.x64` 这类带加号的 ID。ID 不含点（msstore 的 `9NBLGGH4NNS1`）时退回右锚定解析：从行尾倒推源/可用/版本，其左侧一词即 ID，并用「含字母、非纯数字点分串」排除版本号误判。

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
