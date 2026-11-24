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
├── autotheme.ini              <- config\
└── restart.ps1                <- scripts\（回退方案才用到）
```

并在 `cfg.json` 的 `plugin` 数组中加入 `mn.cc.arae.autotheme`。

也可以手工复制并改 `cfg.json`。

## 配置（autotheme.ini）

| 键 | 默认 | 说明 |
|---|---|---|
| `Enable` | 1 | 是否启用自动切换 |
| `Light` | `BlueWhite-1.json` | 系统浅色时的主题；**留空 = 默认主题** |
| `Dark` | `Black-1.json` | 系统深色时的主题；留空同上 |
| `UseInternalCall` | 1 | 1=调用软件自身主题函数（无重启，推荐）；0=回退到重启方案 |
| `AutoRestart` | 1 | 回退方案中是否自动重启；重启等到窗口隐藏后执行 |
| `RestartDelayMs` | 900 | 回退方案中结束旧进程前的等待 |
| `DebugLog` | 0 | 1=写 `autotheme.log` / `restart.log` 便于排查 |

`Light` / `Dark` 留空或不写，对应 `cfg.json` 中的 `"theme":""`（默认主题）。

## 行为

- 监听 `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme`，变化后立即切换主题。
- 通过内部函数在进程内应用主题（主线程），**不重启应用**，不会重新注册热键。
- 同时写回 `cfg.json` 的 `theme`，保证下次启动一致。
- 主题已正确时不做任何操作，无循环。
- 在设置页禁用插件后，宿主会把它从 `cfg.json` 的 `plugin` 列表移除，不再接管。

## 回退方案

若软件更新导致内部 RVA 失效：把 `UseInternalCall=0`。此时写配置后会在**窗口隐藏时**重启应用来应用主题（重启会短暂打断热键注册）。
