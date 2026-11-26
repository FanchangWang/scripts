# 安装与配置

## 安装

推荐用 `scripts\install.ps1`（需先完全退出 Maye Nano）：

```powershell
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\install.ps1
```

默认目标目录是 `C:\Users\guyue\AppData\Local\Programs\MayeNano`，可用 `-TargetDir` 覆盖。脚本会复制：

```
Maye Nano 安装目录\plugin\mn.cc.arae.autotheme\
├── mn.cc.arae.autotheme.dll   <- dist\
└── autotheme.ini              <- config\
```

并在 `cfg.json` 的 `plugin` 数组中加入 `mn.cc.arae.autotheme`。

## 配置（autotheme.ini）

| 键 | 默认 | 说明 |
|---|---|---|
| `Enable` | 1 | 是否启用自动切换 |
| `Light` | 空 | 系统浅色时的主题；**留空 = 默认主题**（`cfg.json` 中 `"theme":""`） |
| `Dark` | `Black-1.json` | 系统深色时的主题；留空同上 |
| `OpenWithSettings` | 1 | 1=打开软件设置窗口时自动弹出插件设置窗口；0=不自动弹 |
| `DebugLog` | 0 | 1=写 `autotheme.log` 便于排查 |

插件目录下会自动生成 `first-run.done` 标记：首次安装后第一次启动会自动弹一次设置窗口，之后不再因为首次安装而弹出。

下拉列表从 `theme\*.json` 动态读取，第一项固定为 `Default`。

## 行为

- 监听 `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme`，变化后立即切换主题。
- 直接调用软件自身的主题应用函数（主线程），**不重启应用**，不重新注册热键。
- 同时写回 `cfg.json` 的 `theme`，保证下次启动一致。
- **设置窗口联动**：软件设置窗口打开时（原生窗口 class=`ImGui Platform`、title=`Settings`），插件设置窗口自动弹出；软件设置窗口关闭时，若插件窗口未被手动关闭则自动隐藏。可用 `OpenWithSettings` 关掉自动弹出。插件窗口也可手动关闭，或通过 `设置 → 插件 → AutoTheme → 右侧「···」→ 选项` 打开。应用设置窗口打开时**不暂停**自动切换。
- 窗口自动定位在软件设置窗口 / 主窗口附近，不再固定在屏幕左上角；标题为「Auto Theme 插件设置」；支持高 DPI 缩放；自身跟随 Windows 深浅色换肤（深色标题栏 / 背景 / 控件），版本号显示在窗口右下角。
- **窗口置顶**读取软件 `cfg.json` 的 `IsTopMost`。主题下拉展开期间暂停周期性置顶，避免窗口盖住自己的下拉列表。
- **主题预览**：插件设置窗口打开时暂停自动切换；点 Light / Dark 下拉右侧「预览主题」可临时套用该主题，按钮变「取消预览」。两个按钮互斥；点「取消预览」、点「保存」或关闭窗口后，恢复按当前 Windows 模式自动切换。
- 窗口顶部说明其用途，并显示同一行的“支持版本 / 当前版本 / 匹配结果”。版本一致时显示“版本匹配，功能可用”；版本不一致时会尝试自动适配，失败则功能静默并提示更新插件或禁用插件。
- 启动时检查 `Light` / `Dark` 配置的主题文件是否存在；丢失则自动弹出插件设置窗口并提示“主题文件丢失”，可在下拉框重新选择并保存。
- 主题已正确时不做任何操作，无循环。

## 版本兼容

已内置版本表（直接查表，无需分析）：7.0.1.261107 → `0x1819D0`，6.2.0.260501 → `0x17D970`，6.1.0.260422 → `0x17D920`，6.0.0.260417 → `0x17B150`。

未列入的版本如果代码结构相近，插件会尝试通过 `.\theme\{}` 字符串的引用来定位新的函数地址；定位失败则静默降级，不会崩溃。
