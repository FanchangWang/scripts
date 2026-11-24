# Linux local toolchain generator

Linux counterpart of `../powershell/toolchain`. Generates a self-contained `local-toolchain-linux` skill for OpenCode on the Linux laptop.

## Generated output

```
~/.agents/skills/local-toolchain-linux/
├── SKILL.md
├── inventory.json
├── inventory.md
└── query.sh
```

## Source files

- `setup.sh` — apt 已装 git/jq/openjdk-17；脚本安装/补齐：uv+Python 3.12、Node 26（最新主线，REA 需要）、JDK 25（Ghidra/REA）、Android SDK（API 19 与 37、build-tools 19.1/35/37、NDK 29/30）、gh、OpenCode V2（`@opencode/cli`，不是 `opencode-ai`）、REA MCP wrapper、apktool/jadx/androguard/binwalk，并创建 `~/code`、`~/code-ref`、`~/code-tools`。网络：GitHub release/raw 走加速域名 `https://github.allproxy.dpdns.org/<完整 https://github.com/... URL>`（注意是把完整原 URL 作为路径拼接，不是 `owner/repo/...` 直拼，否则 404/502）；GitHub API 与小文件按需走 `192.168.31.50:7890` 代理；npm 走 `registry.npmmirror.com`；PyPI 与 Android SDK 直连（代理会让 pythonhosted TLS 失败、让 SDK 大 zip `bad_record_mac`）。`rea-setup.sh` 只装免费 REA 工具，付费 IDA/JEB/Hopper 跳过，Kuna 因 REA 标注 Linux 不支持而跳过。
- `toolchain.sh` — scanner/generator/query CLI (`refresh`, `list`, `find`, `config`, `doctor`, `help`).
- `overrides.json` — durable conventions and config facts.
- `query-template.sh` — generated AI-facing `query.sh`.
- `skill-template.md` — generated `SKILL.md` wording.

## Transfer and run (from Windows Git Bash)

```bash
scp -r /c/Users/guyue/code/scripts/linux/toolchain guyue@192.168.31.52:~/code-tools/
ssh guyue@192.168.31.52 "find ~/code-tools/toolchain -name '*.sh' -exec sed -i 's/\r$//' {} +"
ssh guyue@192.168.31.52 "bash ~/code-tools/toolchain/setup.sh"
ssh guyue@192.168.31.52 "bash ~/code-tools/toolchain/toolchain.sh refresh"
```

## AI-facing queries (on Linux)

```bash
bash ~/.agents/skills/local-toolchain-linux/query.sh find python
bash ~/.agents/skills/local-toolchain-linux/query.sh config
bash ~/.agents/skills/local-toolchain-linux/query.sh doctor
```

## Editing rules

Do not hand-edit generated files; edit source and run `toolchain.sh refresh`.
