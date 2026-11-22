---
name: local-toolchain
description: Report which programming languages, runtimes, package managers, CLIs, Git/GitHub tools, proxy/registry settings, and REA providers are installed on this Windows machine. Use before claiming a tool is missing, choosing a tool command, or scanning the filesystem. This generated skill contains the machine-readable inventory and configuration facts.
metadata:
  version: "1"
---

# Local toolchain inventory

> Generated file. Do not edit by hand. All AI-facing data and tools are inside this skill directory.

## Entry points

- Skill ID: `local-toolchain`.
- If the agent supports skills, load it by ID `local-toolchain`.
- If the agent does not support skills, give it this `SKILL.md` file path directly.
- Machine-readable source of truth: `inventory.json` in this skill directory (absolute: `{{MANIFEST_PATH}}`).
- Human-readable mirror: `inventory.md` in this skill directory (absolute: `{{MARKDOWN_PATH}}`).
- AI-only query helper: `query.ps1` in this skill directory (absolute: `{{QUERY_PATH}}`). It reads only the `inventory.json` beside it; no files outside this skill directory are needed.

Before answering whether a tool, runtime, package manager, or provider is installed:

1. Read `inventory.json` in this skill directory.
2. Use `pwsh.exe -NoProfile -File "{{QUERY_PATH}}" find <query>` for a filtered view.
3. Use `pwsh.exe -NoProfile -File "{{QUERY_PATH}}" config` for machine-specific configuration facts.
4. Use `pwsh.exe -NoProfile -File "{{QUERY_PATH}}" doctor` for a quick core-tool check.
5. Do **not** look for or run generator/source scripts; this skill directory is the only thing AI needs.
6. Do **not** scan the whole filesystem when the manifest already answers the question.

## Important machine-specific rules

- **npm / npx / pnpm** use `https://registry.npmmirror.com/`. npm and npx are 12.2.3; pnpm is 12.21.0.
- **Go** uses `GOPROXY=https://goproxy.cn,direct`.
- **Python** is managed by `uv`. There is no global `python`, `python3`, or `pip` on PATH.
  - Run scripts: `uv run python script.py`
  - One-off tool CLI: `uvx <tool>`
  - Persistent tool CLI: `uv tool install <tool>`
  - `uv pip` is only for an active virtual environment.
- **Proxy** is `http://192.168.31.50:7890` for GitHub, Google, and other blocked sites. Git already has `http.proxy` and `https.proxy` set in `C:\Users\guyue\.gitconfig`. Other tools may need `HTTP_PROXY`/`HTTPS_PROXY` set in their own process.
- **GitHub release and raw URLs** use the accelerator `https://github.allproxy.dpdns.org`. Keep normal `github.com` for API and git clone unless the user says otherwise.
- **Git** is 2.56.1 with `core.autocrlf=true`, VS Code as editor, and `gh.exe` as the GitHub credential helper. Git Bash is at `C:\Program Files\Git\bin\bash.exe`; `bash` on PATH is not Git Bash.
- **gh CLI** is 2.105.0. Use it for GitHub auth/API operations.
- **Windows automation** should use `.cmd` launchers: `npm.cmd`, `npx.cmd`, `pnpm.cmd`, `bun.cmd`.
- **PowerShell scripts**: run `.ps1` files with `pwsh.exe` (PowerShell 7), not the built-in 5.1 `powershell.exe`. OpenCode's global shell is `pwsh`.
- **Git Bash** is at `C:\Program Files\Git\bin\bash.exe`. Use it when a task needs sh/bash utilities on Windows; `bash` on PATH is not Git Bash.
- **Android**: SDK root is `C:\Users\guyue\AppData\Local\Android\Sdk`; Android Studio is `...\AppData\Local\Programs\Android Studio\bin\studio64.exe`; Android CLI is `...\Sdk\cmdline-tools\latest\bin\android.exe` (1.1.16919734) and replaces deprecated `sdkmanager`. The SDK also has NDK 29/30, emulator 37.3.10.0, build-tools, platforms, and system images. REA only uses `platform-tools\adb.exe`.
- **Java installations**: the manifest lists every Java home found (8 JDK/JRE x64/x86, Zulu 11 JRE, 17, 21, two JDK 25 installs, and Android Studio's bundled JBR). The process `JAVA_HOME` is JDK 17; REA overrides it to the JDK 25 at `C:\Users\guyue\code-tools\runtimes\jdk-25.0.5.1\jdk-25.0.5.1+2`.
- **REA MCP** is disabled by default. Its single source of truth is `C:\Users\guyue\.local\bin\rea-mcp.cmd`; clients use that absolute path with no `environment` block. Connect/disconnect through the OpenCode API only when needed. Do not run `rea-agents setup --client`; skill-only updates are safe.
- **Doubao MCP** is the one exception: its bundled Node is 22.23.2, below REA's 22.23.4 floor. Prefix `C:\Program Files\nodejs` to `PATH` in Doubao's own MCP env; never add that PATH line to `rea-mcp.cmd`.
- **TShark/capinfos** are Wireshark 4.6.10 without Npcap: offline pcap parsing only, live capture remains Reqable.
- **Kuna 1.1003** is installed, but REA 6.10.1 expects 1.1000 and reports `unsupported_host` on Windows. That warning is expected.
- **Ghidra 12.2** requires Java 25; `rea-mcp.cmd` overrides `JAVA_HOME` to the active JDK 25 even though the process `JAVA_HOME` is JDK 17.

## Maintenance

- This skill directory is self-contained for AI use: `inventory.json`, `inventory.md`, `query.ps1`, and this `SKILL.md`.
- Do not hand-edit any of these generated files; they are rebuilt by the toolchain generator.
- If data looks stale, tell the user the manifest needs refreshing rather than editing it or scanning the filesystem.
