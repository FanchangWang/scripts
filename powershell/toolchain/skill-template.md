---
name: local-toolchain
description: Inventory of this Windows machine - languages, runtimes, package managers, CLIs, Git/GitHub tooling, proxy and registry settings, and REA providers. Use whenever a task touches local tooling, versions, paths, or asks whether something is installed, and before claiming a tool is missing, choosing a command, or scanning the filesystem. Never assert a tool is absent without reading inventory.json.
metadata:
  version: "1"
---

STOP: if you are about to say a tool is not installed, read `inventory.json` first.

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

## Binding rules

These are constraints, not observations. They apply even when the manifest is not loaded.

- **Never claim a tool is missing without the manifest.** Read `inventory.json`, or run `query.ps1 find <name>`, before saying something is not installed.
- **Do not scan the filesystem** to discover tools. The manifest already answers inventory questions.
- **Python is `uv`-managed.** There is no global `python`, `python3`, or `pip` on PATH. Run scripts with `uv run python <file>.py`, one-off CLIs with `uvx <tool>`, reusable CLIs with `uv tool install <tool>`. `uv pip` only works inside an active virtual environment.
- **Windows automation uses `.cmd` launchers**: `npm.cmd`, `npx.cmd`, `pnpm.cmd`, `bun.cmd`. `.ps1` shims are for interactive PowerShell only.
- **Run `.ps1` with `pwsh.exe`** (PowerShell 7), never the built-in Windows PowerShell 5.1 `powershell.exe`.
- **Git Bash is `C:\Program Files\Git\bin\bash.exe`.** `bash` on PATH resolves to `C:\WINDOWS\system32\bash.exe` and is not Git Bash.
- **Use `gh.exe`** for GitHub auth and API work; it is also the git credential helper for github.com and gist.
- **Never run `rea-agents setup --client`.** REA MCP is disabled by default; clients normally point at the wrapper path with no `environment` block (Doubao is the documented exception). Skill-only updates are safe.
- **Do not modify `rea-mcp.cmd` as a client workaround.** It is hand-maintained; client-side fixes such as the Doubao PATH override belong in that client's own MCP environment.

## Fact summary

Convenience only. The authoritative copy of everything below is `config_facts` in `inventory.json`; if the two disagree, the manifest wins. Read it with `pwsh.exe -NoProfile -File "{{QUERY_PATH}}" config`.

- **npm / npx / pnpm** use `https://registry.npmmirror.com/`. npm and npx are 12.2.3; pnpm is 12.21.0.
- **Go** uses `GOPROXY=https://goproxy.cn,direct`.
- **Proxy** is `http://192.168.31.50:7890` for GitHub, Google, and other blocked hosts. Git already has `http.proxy` and `https.proxy` set in `C:\Users\guyue\.gitconfig`; other tools may need `HTTP_PROXY`/`HTTPS_PROXY` in their own process.
- **GitHub release and raw URLs** use the accelerator `https://github.allproxy.dpdns.org/<FULL https://github.com/... URL>` - the full original URL is the path, e.g. `https://github.allproxy.dpdns.org/https://github.com/OWNER/REPO/releases/download/v1/a.zip`. Host-only replacement returns 404/502. Keep normal `github.com` for API and git clone unless the user says otherwise.
- **Git** is 2.56.1 with `core.autocrlf=true`, VS Code as editor, and `gh.exe` as the GitHub credential helper.
- **gh CLI** is 2.105.0.
- **Android**: SDK root is `C:\Users\guyue\AppData\Local\Android\Sdk`; Android Studio is `...\AppData\Local\Programs\Android Studio\bin\studio64.exe`; Android CLI is `...\Sdk\cmdline-tools\latest\bin\android.exe` (1.1.16919734) and replaces deprecated `sdkmanager`. The SDK also has NDK 29/30, emulator 37.3.10.0, build-tools, platforms, and system images. REA only uses `platform-tools\adb.exe`.
- **Java installations**: the manifest lists every Java home found (Java 8 JRE x64/x86 plus the Oracle `java8path` alias, Zulu 11 JRE, 17, 21, the winget JDK 25, and Android Studio's bundled JBR). Prefer non-versioned junctions such as `C:\Program Files\Java\latest\jdk-21` and `C:\Program Files\Java\latest\jre-1.8`. The process `JAVA_HOME` is JDK 17; REA overrides it to the winget JDK 25 at `C:\Program Files\Microsoft\jdk-25.0.5.102-hotspot`.
- **REA MCP** single source of truth is `C:\Users\guyue\.local\bin\rea-mcp.cmd`, launched as `"C:\Program Files\nodejs\npx.cmd" -y rea-agents@latest mcp`.
- **Doubao MCP** is the one exception on Node: its bundled Node is 22.23.2, below REA's 22.23.4 floor. Prepend `C:\Program Files\nodejs` to `PATH` in Doubao's own MCP env.
- **TShark/capinfos** are Wireshark 4.6.10 without Npcap: offline pcap parsing only, live capture remains Reqable.
- **Kuna 1.1003** is installed, but REA 6.10.1 expects 1.1000 and reports `unsupported_host` on Windows. That warning is expected.
- **Ghidra 12.2** requires Java 25; `rea-mcp.cmd` overrides `JAVA_HOME` to the active JDK 25 even though the process `JAVA_HOME` is JDK 17.

## Maintenance

- This skill directory is self-contained for AI use: `inventory.json`, `inventory.md`, `query.ps1`, and this `SKILL.md`.
- Do not hand-edit any of these generated files; they are rebuilt by the toolchain generator.
- If data looks stale, tell the user the manifest needs refreshing rather than editing it or scanning the filesystem.
