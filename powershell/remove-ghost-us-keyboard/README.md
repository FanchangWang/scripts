# remove-ghost-us-keyboard

临时添加再移除 en-US 语言项，刷新 Windows 用户语言列表，消除游戏 / 应用误切换出来的「幽灵」美式键盘输入法。不改注册表、不下载语言包。

## 环境要求

- Windows + PowerShell 7（`pwsh.exe`）
- 无需管理员权限

## 用法

```powershell
# 常规运行：添加再移除 en-US
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\remove-ghost-us-keyboard\Remove-GhostUSKeyboard.ps1"

# 从备份恢复语言列表
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\remove-ghost-us-keyboard\Remove-GhostUSKeyboard.ps1" -RestoreFrom "C:\Users\guyue\code\scripts\powershell\remove-ghost-us-keyboard\backups\language-list-backup-20260101-120000.json"
```

## 行为说明

1. 读取当前 `Get-WinUserLanguageList`；若列表里已存在 `en-US`，判定为用户有意配置，打印当前语言列表后直接退出，不做任何修改。
2. 将原始语言列表导出到脚本目录下 `backups/language-list-backup-<时间戳>.json`（该目录由 `.gitignore` 忽略），包含每种语言的 `language_tag` 与 `input_method_tips`。采用 JSON 而非 `Export-Clixml`，因为后者反序列化为 `PSObject`，无法直接传回 `Set-WinUserLanguageList -LanguageList`。
3. `Add('en-US')` → `Set-WinUserLanguageList -Force`；重新读取确认 en-US 已加入，否则中止。
4. 从工作列表移除全部 en-US 项 → 再次 `Set-WinUserLanguageList -Force`。
5. 复查最终列表：若仍残留 en-US，打印 `LanguageTag` / `InputMethodTips` 并提示人工核对；否则打印最终列表，并提示必要时注销重新登录或重启 Windows。

## 异常与恢复

- 异常时**先回滚再报错**：用启动时保存的内存副本还原语言列表。这一点很关键——若在 `catch` 中用 `Write-Error`，在 `$ErrorActionPreference='Stop'` 下会直接终止脚本，导致回滚代码全部被跳过，把语言列表留在「已加 en-US、未移除」的中间态。
- 若自动回滚也失败，脚本会打印 `-RestoreFrom` 的恢复命令，用 `backups/` 下的 JSON 备份重建语言列表与输入法提示。
- 系统提示「显示语言将在下次登录后生效」属预期现象。

## 安全设计

- 已有 en-US 时拒绝改动，避免误删用户有意配置的语言。
- 修改前导出 `backups/` JSON 备份，异常时自动回滚；回滚失败时提供可执行的恢复路径。
- 全程只操作用户语言列表，不改注册表、不下载语言包。
