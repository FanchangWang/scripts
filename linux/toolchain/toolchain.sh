#!/usr/bin/env bash
# Scanner/generator/query CLI for the Linux local-toolchain skill.
set -uo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
OVERRIDES="$SRC/overrides.json"
SKILL_DIR="$(python3 -c 'import json,os,sys; d=json.load(open(sys.argv[1],encoding="utf-8")); print(os.path.expanduser(d["generated_skill_dir"]))' "$OVERRIDES")"

case "${1:-help}" in
  refresh)
    mkdir -p "$SKILL_DIR"
    python3 - "$OVERRIDES" "$SKILL_DIR" <<'PY'
import json, os, shutil, subprocess, sys, datetime

overrides_path, skill_dir = sys.argv[1], sys.argv[2]
ov = json.load(open(overrides_path, encoding="utf-8"))
home = os.path.expanduser("~")
for root in ov.get("path_roots", []):
    p = os.path.expanduser(root)
    if p not in os.environ.get("PATH", "").split(os.pathsep):
        os.environ["PATH"] = p + os.pathsep + os.environ.get("PATH", "")

TOOLS = [
    ("python3", "Python", "runtime", "python3 -V 2>&1 | head -1"),
    ("uv", "uv", "package-manager", "uv --version 2>&1 | head -1"),
    ("node", "Node.js", "runtime", "node -v"),
    ("npm", "npm", "package-manager", "npm -v"),
    ("npx", "npx", "package-manager", "npx -v"),
    ("pnpm", "pnpm", "package-manager", "pnpm -v"),
    ("git", "Git", "vcs", "git --version"),
    ("gh", "GitHub CLI", "cli", "gh --version 2>&1 | head -1"),
    ("opencode", "OpenCode", "cli", "opencode --version 2>&1 | head -1"),
    ("java", "Java (default)", "runtime", "java -version 2>&1 | head -1"),
    ("java17", "Java 17 wrapper", "runtime", "java17 -version 2>&1 | head -1"),
    ("java25", "Java 25 wrapper", "runtime", "java25 -version 2>&1 | head -1"),
    ("javac", "javac", "build", "javac -version 2>&1 | head -1"),
    ("adb", "Android Debug Bridge", "sdk", "adb version 2>&1 | head -1"),
    ("sdkmanager", "Android SDK Manager", "sdk", "echo installed"),
    ("apktool", "Apktool", "binary-analysis", "echo installed"),
    ("jadx", "JADX", "binary-analysis", "echo installed"),
    ("androguard", "Androguard", "binary-analysis", "echo installed"),
    ("binwalk", "Binwalk", "binary-analysis", "echo installed"),
    ("ghidra", "Ghidra", "binary-analysis", "echo installed"),
    ("jadx-headless-mcp", "JADX Headless MCP", "mcp", "echo installed"),
    ("rizin", "Rizin", "binary-analysis", "echo installed"),
    ("tshark", "TShark", "network", "tshark --version 2>&1 | head -1"),
    ("capinfos", "capinfos", "network", "capinfos --version 2>&1 | head -1"),
    ("wabt", "WABT", "binary-analysis", "echo installed"),
    ("ilspycmd", "ilspycmd", "binary-analysis", "echo installed"),
    ("chrome", "Chrome for Testing", "network", "echo installed"),
    ("rea-mcp", "REA MCP wrapper", "mcp", "echo installed"),
    ("jq", "jq", "cli", "jq --version"),
    ("curl", "curl", "cli", "curl --version 2>&1 | head -1"),
    ("wget", "wget", "cli", "wget --version 2>&1 | head -1"),
    ("unzip", "unzip", "cli", "echo installed"),
    ("zip", "zip", "cli", "echo installed"),
    ("tar", "tar", "cli", "echo installed"),
]

def probe(tool_id, version_cmd):
    path = shutil.which(tool_id)
    if not path:
        return "missing", "", ""
    try:
        r = subprocess.run(version_cmd, shell=True, capture_output=True, text=True, timeout=20)
        version = (r.stdout or r.stderr).strip().splitlines()[0][:160] if (r.stdout or r.stderr).strip() else ""
    except Exception:
        version = ""
    return "installed", path, version

tools = []
for tid, name, cat, vcmd in TOOLS:
    status, path, version = probe(tid, vcmd)
    tools.append({"id": tid, "name": name, "category": cat,
                  "status": status, "path": path, "version": version})

data = {
    "generated_at": datetime.datetime.now().isoformat(timespec="seconds"),
    "schema_version": 1,
    "scope": ov.get("scope", ""),
    "generated_by": ov.get("generated_by", ""),
    "skill_id": ov["skill_id"],
    "summary": {"total": len(tools)},
    "tools": tools,
    "config_facts": ov.get("config_facts", []),
    "conventions": ov.get("conventions", []),
    "env_facts": ov.get("env_facts", {}),
    "project_dirs": ov.get("project_dirs", {}),
    "path_roots": ov.get("path_roots", []),
}

with open(os.path.join(skill_dir, "inventory.json"), "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

lines = ["# Linux Toolchain Inventory", "",
         f"- Generated: {data['generated_at']}",
         f"- Skill ID: {data['skill_id']}", "",
         "| id | name | category | status | path | version |",
         "|---|---|---|---|---|---|"]
for t in tools:
    lines.append(f"| {t['id']} | {t['name']} | {t['category']} | {t['status']} | {t['path']} | {t['version'][:80]} |")
lines += ["", "## Config facts", "", "| id | title | value | usage |", "|---|---|---|---|"]
for fact in ov.get("config_facts", []):
    lines.append(f"| {fact.get('id','')} | {fact.get('title','')} | {str(fact.get('value',''))[:80]} | {str(fact.get('usage',''))[:120]} |")
lines += ["", "## Conventions", ""]
for c in ov.get("conventions", []):
    lines.append("- " + c)
lines += ["", "## Env facts", ""]
for k, v in ov.get("env_facts", {}).items():
    lines.append(f"- {k} = {v}")
with open(os.path.join(skill_dir, "inventory.md"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")

src = os.path.dirname(os.environ.get("TOOLCHAIN_SRC", overrides_path))
# query/skill templates are copied by the shell caller; see below.
print(f"generated inventory: {len(tools)} tools -> {skill_dir}")
PY
    cp "$SRC/query-template.sh" "$SKILL_DIR/query.sh"
    cp "$SRC/skill-template.md" "$SKILL_DIR/SKILL.md"
    chmod +x "$SKILL_DIR/query.sh"
    echo "skill written to $SKILL_DIR"
    ;;
  list|find|config|doctor)
    bash "$SKILL_DIR/query.sh" "$@"
    ;;
  help)
    echo "Usage: toolchain.sh refresh | list | find <query> | config | doctor | help"
    ;;
  *)
    echo "Unknown command: $1" >&2
    exit 1
    ;;
esac
