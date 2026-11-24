#!/usr/bin/env bash
# AI-only query helper. Reads only inventory.json beside this script.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
INV="$HERE/inventory.json"

JSON=0
if [ "${!#}" = "--json" ]; then
  JSON=1
  set -- "${@:1:$#-1}"
fi
export JSON

CMD="${1:-help}"
ARG="${2:-}"

case "$CMD" in
  find)
    python3 - "$INV" "$ARG" <<'PY'
import json, os, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
q = sys.argv[2].lower()
items = [t for t in data["tools"] if q in (t["id"] + " " + t["name"] + " " + t["category"] + " " + t["path"]).lower()]
if os.environ.get("JSON") == "1":
    print(json.dumps(items, ensure_ascii=False, indent=2))
else:
    print(f"Matches for '{sys.argv[2]}': {len(items)}")
    for t in items:
        print(f"{t['id']:18} {t['name']:24} {t['category']:18} {t['status']:12} {t['path']}  {t['version']}")
PY
    ;;
  config)
    python3 - "$INV" <<'PY'
import json, os, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
facts = data.get("config_facts", [])
j = os.environ.get("JSON") == "1"
if j:
    print(json.dumps(facts, ensure_ascii=False, indent=2))
elif isinstance(facts, list) and facts:
    for f in facts:
        print(f"{f.get('id','')}: {f.get('title','')}")
        print(f"  value: {f.get('value','')}")
        print(f"  usage: {f.get('usage','')}")
else:
    print("Conventions:")
    for c in data.get("conventions", []):
        print(" -", c)
    print("\nEnv facts:")
    for k, v in data.get("env_facts", {}).items():
        print(f" {k} = {v}")
    print("\nProject dirs:")
    for k, v in data.get("project_dirs", {}).items():
        print(f" {k} = {v}")
PY
    ;;
  doctor)
    python3 - "$INV" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
missing = [t["id"] for t in data["tools"] if t["status"] != "installed"]
total = data.get("summary", {}).get("total", len(data["tools"]))
print(f"doctor: installed {total - len(missing)} / {total}")
print("missing:", ", ".join(missing) if missing else "none")
PY
    ;;
  list)
    python3 - "$INV" <<'PY'
import json, os, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if os.environ.get("JSON") == "1":
    print(json.dumps(data["tools"], ensure_ascii=False, indent=2))
else:
    for t in data["tools"]:
        print(f"{t['id']:18} {t['status']:12} {t['path']}")
PY
    ;;
  summary)
    python3 - "$INV" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
print(json.dumps({"summary": data.get("summary", {}), "generated_at": data.get("generated_at"), "scope": data.get("scope", "")}, ensure_ascii=False, indent=2))
PY
    ;;
  *)
    echo "Usage: query.sh list|find <query>|config|doctor|summary|help  [add --json to list/find/config]"
    ;;
esac
