---
name: Linux Local Toolchain
description: Inventory of this Linux Mint laptop's toolchain — uv/Python, Node/npm/pnpm, JDK/Android SDK, git/gh, OpenCode, REA MCP and reverse-engineering tools. Use whenever a task touches local tooling, versions, paths, or asks whether something is installed; read inventory.json or run query.sh before claiming a tool is missing.
metadata:
  version: "1"
---

# Linux local toolchain

This skill is **generated**. Do not hand-edit its files; regenerate from `~/code-tools/toolchain/` with `toolchain.sh refresh`.

## Entry points

- Machine-readable source of truth: `inventory.json` in this skill directory.
- Human-readable mirror: `inventory.md`.
- AI-only helper: `query.sh` in this skill directory.

Before answering whether a tool/runtime/package manager is installed, run one of:

```bash
bash ~/.agents/skills/local-toolchain-linux/query.sh find <query>
bash ~/.agents/skills/local-toolchain-linux/query.sh config
bash ~/.agents/skills/local-toolchain-linux/query.sh doctor
bash ~/.agents/skills/local-toolchain-linux/query.sh summary
bash ~/.agents/skills/local-toolchain-linux/query.sh config --json
```

## Rules

- Never claim a tool is missing without checking `inventory.json` or `query.sh find`.
- Do not scan the whole filesystem when the manifest answers the question.
- Python is uv-managed: `uv run python <file>.py`, `uvx <tool>`, `uv tool install <tool>`; there is no global pip on PATH.
- Node globals go to `~/.local/bin` because npm prefix is `~/.local`; use `npm.cmd`-free POSIX commands directly.
- Java: `JAVA_HOME` is apt openjdk-17-jdk (Android 4.4 builds); use `java25` / `JDK25_HOME` for Ghidra/REA.
- Android SDK: `ANDROID_HOME` / `ANDROID_SDK_ROOT` = `~/code-tools/android-sdk`, `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` = `~/code-tools/android-sdk/ndk/30.0.16248370`; it has API 19 (Android 4.4) and API 37 (Android 17), NDK 29/30; `adb` is in `platform-tools`.
- REA MCP wrapper is `~/.local/bin/rea-mcp`; never run `rea-agents setup --client`.
- All installs are user-space; no sudo after `apt update/upgrade`.
- Proxy `192.168.31.50:7890` is **on-demand only** (setup.sh helpers or explicit wrapper), never a global env var in `~/.bashrc`; LAN/direct traffic must not be proxied.
- GitHub release/raw URLs use the accelerator `https://github.allproxy.dpdns.org/<完整 https://github.com/... URL>` — the full original URL is the path. Host-only replacement (`.../OWNER/REPO/...`) returns 404/502.
- Project dirs: `~/code` (own projects), `~/code-ref` (third-party sources), `~/code-tools` (portable tools).

## Scope

Inventory covers tools declared in `overrides.json` and detected on PATH plus known install roots. Generated directories are excluded unless the project customizes them.
