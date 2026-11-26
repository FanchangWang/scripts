# Local toolchain generator

Generates a complete, self-contained `local-toolchain` skill containing:

- `inventory.json` - machine-readable tool and configuration inventory
- `inventory.md` - human-readable mirror
- `query.ps1` - AI-only query helper; reads only `inventory.json` beside it
- `SKILL.md` - agent-facing entry point

Generated output defaults to:

```text
C:\Users\guyue\.agents\skills\local-toolchain
```

The `scripts` repository keeps source only.

## Source files

- `toolchain.ps1` - scanner, refresh queries, Markdown generator, skill generator
- `refresh.ps1` - compatibility wrapper for `refresh`
- `overrides.json` - durable conventions and configuration facts
- `update-channels.json` - authoritative install/update channels per tool (do not update a tool through a different channel; `source` in `toolchain.ps1` may be stale)
- `rea-env.json` - read-only REA wrapper validation config (env var -> tool id mapping)
- `query-template.ps1` - template for the generated skill-local `query.ps1`
- `skill-template.md` - template for the generated `SKILL.md`
- `AGENTS.md` - project instructions for agents working in this directory

## AI-facing queries

Agents use only the generated skill directory, never this source directory:

```powershell
pwsh.exe -NoProfile -File "C:\Users\guyue\.agents\skills\local-toolchain\query.ps1" find python
pwsh.exe -NoProfile -File "C:\Users\guyue\.agents\skills\local-toolchain\query.ps1" config
pwsh.exe -NoProfile -File "C:\Users\guyue\.agents\skills\local-toolchain\query.ps1" doctor
```

## REA wrapper read-only check

`rea-check` validates `C:\Users\guyue\.local\bin\rea-mcp.cmd` against `inventory.json`. It never writes to the wrapper.

```powershell
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" rea-check
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" rea-check -Json
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" rea-check -WrapperPath <copy.cmd>
```

It checks:

- no UTF-8 BOM and CRLF line endings (wrapper is CP936/GBK, no BOM, CRLF)
- active launch command `npx.cmd -y rea-agents@latest mcp`
- every mapped env var is either an active `set` or a `REM set` placeholder
- active paths exist and match the active tool path in the inventory (directories may be parents of executable paths)
- a mapped env var left as `REM` while its tool is installed
- version drift (for example apktool pointing at the retained 2.9.3 instead of active 3.0.3)

The env-var-to-tool-id mapping, wrapper path, and expected launch command live in `rea-env.json`. Returns exit code 1 if there is any error or warning, 0 when all checks pass.

## Run

PowerShell 7 (`pwsh.exe`) is required. There is no `.cmd` launcher.

```powershell
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" refresh
```

Other commands:

```powershell
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" list
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" find python
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" config
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" doctor
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" help
```

Use `-OutputDirectory <path>` to write generated files elsewhere.

## Editing rules

- Do not hand-edit generated files in the skill directory.
- Edit `overrides.json` for durable conventions/config facts.
- Edit `skill-template.md` for skill wording.
- Edit `query-template.ps1` for the AI-facing query commands.
- Add or change tool probes in `toolchain.ps1`.
- Regenerate with `refresh` after any source change.
