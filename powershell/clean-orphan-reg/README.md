# clean-orphan-reg

定向清理 HiBit Uninstaller 留下的「空镜像」注册表残骸：只删 HKLM 下匹配 `VSCode.*` / `vscode` / `zcode` / `QQMusic.*`、且 HKCU 无同名项、且该项完全为空（无子键无值）的键。跳过 AppX 开头及其他系统项，不破坏 UWP 应用。

## 环境要求

- Windows + PowerShell 7（`pwsh.exe`）
- **必须以管理员身份运行**：删除 HKLM 项需要管理员权限，脚本会先做权限预检，非管理员直接以退出码 1 终止

## 用法

```powershell
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\clean-orphan-reg\Clean-TargetOrphan.ps1"
```

脚本先扫描并列出待删除项，按回车确认后才执行删除；直接关闭窗口即放弃执行。

## 行为说明

1. 权限预检通过后扫描 `HKLM:\SOFTWARE\Classes` 下匹配 `^(VSCode\.|vscode$|zcode$|QQMusic\.)` 的子键；读取失败会明确报错并写日志，不再静默跳过。
2. 仅当 `HKCU:\Software\Classes` 下无同名项、且该 HKLM 项**无子键且无值**时，才加入待删列表；其余匹配项记入日志的「跳过」段并注明原因。
3. 列出全部待删项并等待回车确认。
4. **删除前逐项重新校验**：若 HKCU 同名键已出现、HKLM 项已不存在、或该项已变为非空，则跳过并记录，避免确认期间其他程序重建内容后被误删。
5. 校验通过后逐项 `Remove-Item -LiteralPath -Recurse -Force`，记录成功 / 失败。
6. 全过程写日志到脚本目录下 `logs/Clean_Orphan_Reg_<时间戳>.log`（UTF-8；该目录由 `.gitignore` 忽略，不再落桌面），含匹配规则、待删列表、跳过及原因、结果与失败原因；0 个待删项时也写日志后退出。
7. 结束后提示重启资源管理器（`Stop-Process -Name explorer -Force`）使改动生效。

## 安全设计

- 正则前缀白名单 + 「HKCU 无同名」+ 「完全为空」三重条件，跳过 AppX 与其他系统项。
- 删除前必须人工确认，且确认后再次复核，不做静默批量删除。
- 全程使用 `-LiteralPath`，避免键名中 `[` `]` 等字符被当作通配符造成误删。
- 日志留痕并区分「成功 / 失败 / 跳过」，便于排查权限问题与复核结果。

## 限制 / 备注

- 仅检查当前 PowerShell 进程的注册表视图。64 位 `pwsh.exe` 下不会覆盖 `HKLM:\SOFTWARE\WOW6432Node\Classes` 中的 32 位残骸；若确认仍有残留，需单独处理该视图。
- 原文件名 `Clean‑TargetOrphan.ps1` 中的连字符为 U+2011（不换行连字符），收录时统一为常规 ASCII 连字符 `Clean-TargetOrphan.ps1`。
