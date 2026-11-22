# Toolchain project instructions

- This directory contains **source only**. Generated files belong in the `local-toolchain` skill directory: `C:\Users\guyue\.agents\skills\local-toolchain`.
- Run scripts with `pwsh.exe` (PowerShell 7), not Windows PowerShell 5.1.
- There is no `toolchain.cmd` launcher. Use:
  `pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\powershell\toolchain\toolchain.ps1" refresh`
- `overrides.json` holds durable conventions and configuration facts.
- `rea-env.json` holds the read-only REA wrapper validation mapping; `rea-check` never modifies `rea-mcp.cmd`.
- `query-template.ps1` holds the AI-facing `query.ps1` generated into the skill.
- `skill-template.md` holds the generated `SKILL.md` wording.
- `toolchain.ps1` holds tool probes, refresh queries, and generation logic.
- AI agents must use only the generated skill directory (`query.ps1`, `inventory.json`, `inventory.md`, `SKILL.md`); this source directory is for maintainers.
- Do not hand-edit generated files in the skill directory (`inventory.json`, `inventory.md`, `query.ps1`, `SKILL.md`); regenerate them from here.
- The generated skill ID is `local-toolchain`; global AGENTS/CLAUDE pointer files reference the skill, not generated data paths.
