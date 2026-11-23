# powershell

Windows 系统维护类 PowerShell 脚本集合，统一用 PowerShell 7（`pwsh.exe`）运行。

## 项目索引

| 目录 | 类型 | 一句话用途 | 文档 |
|---|---|---|---|
| [clean-orphan-reg](clean-orphan-reg/) | 命令行脚本（注册表清理） | 定向清理 HiBit Uninstaller 留下的空镜像注册表项 | [README](clean-orphan-reg/README.md) |
| [remove-ghost-us-keyboard](remove-ghost-us-keyboard/) | 命令行脚本（语言列表） | 临时添加再移除 en-US，刷新语言列表以消除幽灵美式键盘 | [README](remove-ghost-us-keyboard/README.md) |
| [toolchain](toolchain/) | 命令行脚本（工具链生成器） | 生成自包含的 `local-toolchain` skill（inventory / query / SKILL） | [README](toolchain/README.md) |

## 共性说明

- 均为 Windows 专用脚本，依赖 PowerShell cmdlet（Registry 提供程序、`Get-WinUserLanguageList` 等），不跨平台。
- 统一用 `pwsh.exe -NoProfile -File <脚本路径>` 运行，不使用 Windows PowerShell 5.1 的 `powershell.exe`。
- 涉及系统状态修改的脚本均带确认与日志；是否提供备份 / 回滚见各项目 README（注册表清理不可回滚）。
