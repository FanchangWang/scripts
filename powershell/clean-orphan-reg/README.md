# clean-orphan-reg

定向清理 HiBit Uninstaller 留下的「空镜像」注册表残骸：只删 HKLM 下匹配 `VSCode.*` / `vscode` / `zcode` / `QQMusic.*`、且 HKCU 无同名项、且该项完全为空（无子键无值）的键。跳过 AppX 开头及其他系统项，不破坏 UWP 应用。

## 环境要求

- Windows + PowerShell 7（`pwsh.exe`）；脚本首行 `#Requires -Version 7.0`，用 5.1 运行会直接拒绝
- **必须以管理员身份运行**：删除 HKLM 项需要管理员权限，脚本会先做权限预检，非管理员直接以退出码 1 终止

## 用法

```powershell
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\clean-orphan-reg\Clean-TargetOrphan.ps1"
```

脚本先扫描并列出待删除项，然后要求输入 `DELETE` 确认；其他任何输入都会放弃。stdin 被重定向（管道 / 计划任务 / CI）时直接拒绝执行。

## 行为说明

1. 权限预检通过后扫描 `HKLM:\SOFTWARE\Classes` 下匹配 `^(VSCode\.|vscode$|zcode$|QQMusic\.)` 的子键；读取失败会明确报错并写日志，不再静默跳过。
2. 仅当 `HKCU:\Software\Classes` 下无同名项、且该 HKLM 项**无子键且无值**时，才加入待删列表；其余匹配项记入日志的「跳过」段并注明原因。
3. 列出全部待删项，先落盘一次日志，再要求输入 `DELETE` 确认。
4. **删除前逐项重新校验**：若 HKCU 同名键已出现、HKLM 项已不存在、或该项已变为非空，则跳过并记录，避免确认期间其他程序重建内容后被误删。
5. 校验通过后 `Remove-Item -LiteralPath -Force` 删除（**不带 `-Recurse`**：目标已证明为空，若竞态中冒出新内容则报错保留，fail-safe），逐项记录成功 / 失败。
6. 日志写到脚本目录下 `logs/Clean_Orphan_Reg_<时间戳>.log`（UTF-8；该目录已 git 忽略），并在确认前、每处理完一项、结束时增量落盘——中途被打断也有记录；日志写入失败会明确提示，不会谎报路径。
7. 结束后提示重启资源管理器（`Stop-Process -Name explorer -Force`）使改动生效。

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 全部成功 / 0 项 / 用户放弃 |
| 1 | 前置条件失败（非管理员、扫描失败、异常回滚失败） |
| 2 | 存在删除失败项（部分成功） |
| 3 | 非交互输入被拒绝（stdin 重定向） |

## 安全设计

- 正则前缀白名单 + 「HKCU 无同名」+ 「完全为空」三重条件，跳过 AppX 与其他系统项。
- 删除前必须显式输入 `DELETE`，且确认后再次复核，不做静默批量删除。
- 全程使用 `-LiteralPath`，避免键名中 `[` `]` 等字符被当作通配符造成误删。
- 日志留痕并区分「成功 / 失败 / 跳过」，中途中断也可追溯。

## 限制 / 备注

- 注册表项删除**不可回滚**，脚本不提供备份恢复。
- 仅检查当前 PowerShell 进程的注册表视图。64 位 `pwsh.exe` 下不会覆盖 `HKLM:\SOFTWARE\WOW6432Node\Classes` 中的 32 位残骸；若确认仍有残留，需单独处理该视图。
- 原文件名 `Clean‑TargetOrphan.ps1` 中的连字符为 U+2011（不换行连字符），收录时统一为常规 ASCII 连字符 `Clean-TargetOrphan.ps1`。
