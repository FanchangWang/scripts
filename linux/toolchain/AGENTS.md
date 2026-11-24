# Linux toolchain project instructions

- This directory contains **source only**. Generated files belong in the Linux skill directory: `~/.agents/skills/local-toolchain-linux/`.
- Run scripts with `bash`; no PowerShell on Linux.
- `setup.sh` installs tools into `$HOME/.local` and `$HOME/code-tools` (user-space, no sudo).
- `toolchain.sh refresh` regenerates the skill from `overrides.json`, `query-template.sh` and `skill-template.md`.
- `overrides.json` holds durable conventions/config facts (PATH roots, SDK roots, proxy).
- AI agents must use only the generated skill directory; this source directory is for maintainers.
- Do not hand-edit generated `inventory.json`, `inventory.md`, `query.sh`, `SKILL.md`.
- Transfer to Linux with `scp -r` and run `sed -i 's/\r$//'` on `*.sh` before executing.
