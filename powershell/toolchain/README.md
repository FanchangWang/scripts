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
