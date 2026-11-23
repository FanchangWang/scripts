#!/bin/bash
# EnvSwitch 配置生命周期验收套件（仓库内版本，源自 audit10 的 acceptance.sh）
#
# 为什么要这份脚本：十轮审计每轮都能挖出新问题，是因为审计靠「抽样场景」，而配置读写是一个
# 有限的状态空间（路径类 × 文件状态 × 入口）。这张表把格子枚举完，每个格子一条断言；
# 代码改动后先跑它，而不是靠读代码猜。
#
# 跑法（Windows，用 git 自带的 bash）：
#   dotnet build -c Release
#   "C:\Program Files\Git\bin\bash.exe" tests/acceptance.sh
#
# 约定：PASS / FAIL（退出码 1）/ SKIP（缺 python 时跳过的解析检查）
# 不写注册表：所有 use/apply 都带 --dry-run；不碰默认路径 %LOCALAPPDATA%\EnvSwitch
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
EXE="$HERE/../bin/Release/net10.0-windows/win-x64/EnvSwitch.exe"
ROOT="$(cygpath -m "${TEMP:-/tmp}")/envsw_accept"
PORT=47720
B="http://127.0.0.1:$PORT"
ok=0; bad=0; skip=0
ckc(){ if [ "$2" = "$3" ]; then echo "PASS    $1"; ok=$((ok+1)); else echo "FAIL    $1  (期望 $2，实际 $3)"; bad=$((bad+1)); fi; }
skp(){ echo "SKIP    $1  ($2)"; skip=$((skip+1)); }
W(){ cygpath -w "$1"; }
run(){ "$EXE" "$@" >/dev/null 2>&1; echo $?; }
st(){ curl -s -o /dev/null -w "%{http_code}" "$@"; }
body(){ curl -s "$@"; }
# 用 python 判「这份文件是不是一份合法 JSON 对象」；没有 python 就退化成只查 .bad
valid_obj(){
  python -c "import json,sys;v=json.load(open(sys.argv[1],encoding='utf-8'));print('yes' if isinstance(v,dict) else 'no')" "$1" 2>/dev/null || echo no
}

rm -rf "$ROOT"; mkdir -p "$ROOT"
GOOD='{"toolchains":[{"name":"KeepMe","envVar":"ENVSWITCH_ACC","pathTemplate":"","enabled":true,"envScope":"User","pathScope":"User","active":"1","versions":[{"name":"1","home":"C:\\Windows","paths":[]}]}],"settings":{"port":47720,"openBrowserOnStart":false}}'
mk(){ mkdir -p "$1"; printf '%s' "$GOOD" > "$1/cfg.json"; }

echo "=== A. CLI 只读：显式路径 × 文件状态 ==="
mk "$ROOT/a1"; ckc "A1 正常配置 → exit=0、无 .bad" "0:0" "$(run list --config "$(W "$ROOT/a1/cfg.json")"):$(ls "$ROOT/a1" | grep -c bad)"
mk "$ROOT/a2"; printf '{ "toolchains": [' > "$ROOT/a2/cfg.json"
    if command -v python >/dev/null 2>&1; then
        run list --config "$(W "$ROOT/a2/cfg.json")" >/dev/null
        ckc "A2 半截 JSON → .bad + 重建后可解析" "1:yes" "$(ls "$ROOT/a2" | grep -c 'bad-'):$(valid_obj "$ROOT/a2/cfg.json")"
    else
        skp "A2 重建后可解析" "本机没有 python"
        ckc "A2 半截 JSON → .bad + 重建" "1" "$(ls "$ROOT/a2" | grep -c 'bad-')"
    fi
mk "$ROOT/a3"; printf '[]' > "$ROOT/a3/cfg.json"
    ckc "A3 合法非对象 [] → 按损坏处理（.bad + 重建）" "0:1" "$(run list --config "$(W "$ROOT/a3/cfg.json")"):$(ls "$ROOT/a3" | grep -c 'bad-')"
mk "$ROOT/a4"; printf 'null' > "$ROOT/a4/cfg.json"
    ckc "A4 字面量 null → 按损坏处理（.bad + 重建），不再用探测配置" "0:1" "$(run list --config "$(W "$ROOT/a4/cfg.json")"):$(ls "$ROOT/a4" | grep -c 'bad-')"
mk "$ROOT/a5"; printf '' > "$ROOT/a5/cfg.json"
    ckc "A5 空文件 → .bad + 重建" "0:1" "$(run list --config "$(W "$ROOT/a5/cfg.json")"):$(ls "$ROOT/a5" | grep -c 'bad-')"
mkdir -p "$ROOT/a6"
    ckc "A6 路径是目录 → exit=1" "1" "$(run list --config "$(W "$ROOT/a6")")"
mkdir -p "$ROOT/a7"; printf 'x' > "$ROOT/a7/notepad.exe"
    ckc "A7 路径中一段是文件 → exit=1" "1" "$(run list --config "$(W "$ROOT/a7")\\notepad.exe\\cfg.json")"
mkdir -p "$ROOT/a8"
    ckc "A8 本地新目录 → 首次运行建出来" "0:yes" "$(run list --config "$(W "$ROOT/a8/deep/sub/cfg.json")"):$(test -f "$ROOT/a8/deep/sub/cfg.json" && echo yes || echo no)"
    ckc "A9 --config 空串 → exit=1" "1" "$(run list --config '')"
mkdir -p "$ROOT/a10" && cd "$ROOT/a10" || exit 1
    ckc "A10 裸文件名 → 当前目录建出来" "0:yes" "$(run list --config rel.json):$(test -f "$ROOT/a10/rel.json" && echo yes || echo no)"
    cd "$ROOT" || exit 1
t0=$(date +%s%3N)
    ckc "A11 黑洞 UNC → exit=1" "1" "$(run list --config '\\192.0.2.1\share\cfg.json')"
echo "      A11 耗时 $(( $(date +%s%3N)-t0 ))ms（应 < 4000ms）"
mk "$ROOT/a12"; printf 'null' > "$ROOT/a12/cfg.json"
    ckc "A12 config 命令对 null 原样打印、不备份（只读命令不做解析/修复）" "0:0" "$(run config --config "$(W "$ROOT/a12/cfg.json")"):$(ls "$ROOT/a12" | grep -c 'bad-')"

echo "=== B. CLI 写入：坏配置必须在写之前停住（全部 --dry-run）==="
mk "$ROOT/b1"; printf '{ "toolchains": [' > "$ROOT/b1/cfg.json"
    run use KeepMe 1 --dry-run --config "$(W "$ROOT/b1/cfg.json")" >/dev/null
    ckc "B1 半截 JSON → 先备份重建再执行（.bad 存在且文件可解析）" "1" "$(ls "$ROOT/b1" | grep -c 'bad-')"
mk "$ROOT/b2"; printf 'null' > "$ROOT/b2/cfg.json"
    run use KeepMe 1 --dry-run --config "$(W "$ROOT/b2/cfg.json")" >/dev/null
    ckc "B2 null → 先备份重建（不再拿探测配置当用户配置）" "1:yes" "$(ls "$ROOT/b2" | grep -c 'bad-'):$(test -s "$ROOT/b2/cfg.json" && echo yes || echo no)"
mkdir -p "$ROOT/b3"
    ckc "B3 路径是目录 → use exit=1" "1" "$(run use KeepMe 1 --dry-run --config "$(W "$ROOT/b3")")"

echo "=== C. serve 请求：只有「可读的合法对象」允许 200 ==="
mkdir -p "$ROOT/srv" "$ROOT/log"; printf '%s' "$GOOD" > "$ROOT/srv/cfg.json"
S=$(W "$ROOT/srv/cfg.json")
"$EXE" serve --port $PORT --no-browser --config "$S" > "$ROOT/log/serve.log" 2>&1 &
SRV=$!; sleep 5
ckc "C1 基线 /api/state = 200" "200" "$(st $B/api/state)"
printf '{ "toolchains": [' > "$ROOT/srv/cfg.json"; SHA=$(sha256sum "$ROOT/srv/cfg.json" | cut -d' ' -f1)
ckc "C2 半截 JSON → state 503"        "503" "$(st $B/api/state)"
ckc "C3 半截 JSON → apply(dry) 503"   "503" "$(st -H 'Content-Type: application/json' -d '{"toolchain":"KeepMe","version":"1","dryRun":true}' $B/api/apply)"
ckc "C4 半截 JSON → apply/all 503"    "503" "$(st -H 'Content-Type: application/json' -d '{"dryRun":true}' $B/api/apply/all)"
ckc "C5 半截 JSON → path/view 503"    "503" "$(st -H 'Content-Type: application/json' -d '{}' $B/api/path/view)"
ckc "C6 三次重复请求只记 1 条 warn"    "1" "$(st $B/api/state >/dev/null; st -H 'Content-Type: application/json' -d '{}' $B/api/path/view >/dev/null; grep -c 'warn:' "$ROOT/log/serve.log")"
ckc "C7 坏文件字节未变"                "$SHA" "$(sha256sum "$ROOT/srv/cfg.json" | cut -d' ' -f1)"
ckc "C8 无 .bad / .tmp 残留"           "0" "$(ls "$ROOT/srv" | grep -cE '\.bad-|\.tmp')"
printf '[]' > "$ROOT/srv/cfg.json"
ckc "C9 合法非对象 [] → state 503"     "503" "$(st $B/api/state)"
printf 'null' > "$ROOT/srv/cfg.json"
ckc "C10 字面量 null → state 503（不得返回探测配置）" "503" "$(st $B/api/state)"
ckc "C10b null → apply(dry) 503"      "503" "$(st -H 'Content-Type: application/json' -d '{"toolchain":"KeepMe","version":"1","dryRun":true}' $B/api/apply)"
printf '%s' "$GOOD" > "$ROOT/srv/cfg.json"
ckc "C11 恢复 → state 200"             "200" "$(st $B/api/state)"

echo "=== D. serve 请求：目录/文件不可用时不重建、不假成功，文案给的是「改回原名」 ==="
mv "$ROOT/srv" "$ROOT/srv.away"
ckc "D1 目录改名 → state 503"          "503" "$(st $B/api/state)"
ckc "D2 目录改名 → POST /api/config 503" "503" "$(st -H 'Content-Type: application/json' -d '{"toolchains":[],"settings":{}}' $B/api/config)"
ckc "D3 旧路径未被重建"                 "no" "$(test -d "$ROOT/srv" && echo yes || echo no)"
mv "$ROOT/srv.away" "$ROOT/srv"
ckc "D4 改回 → state 200"               "200" "$(st $B/api/state)"
rm "$ROOT/srv/cfg.json"
msg=$(body $B/api/state)
case "$msg" in
  *"改回原名"*) if [ "${msg#*稍后重试即可}" = "$msg" ]; then ckc "D5 文件被删 → 503 文案复用 Hint（改回原名，不再说稍后重试）" "ok" "ok"; else ckc "D5 文件被删 → 503 文案复用 Hint" "no-later" "has-later"; fi ;;
  *) ckc "D5 文件被删 → 503 文案复用 Hint" "has-hint" "no-hint" ;;
esac
ckc "D6 文件被删后仍不重建"             "no" "$(test -f "$ROOT/srv/cfg.json" && echo yes || echo no)"
printf '%s' "$GOOD" > "$ROOT/srv/cfg.json"
ckc "D7 放回配置 → state 200"           "200" "$(st $B/api/state)"

echo "=== E. 写入护栏（与服务无关的既有断言）==="
ckc "E1 path/apply [null] → 400"       "400" "$(st -H 'Content-Type: application/json' -d '{"system":[null],"user":null,"dryRun":true}' $B/api/path/apply)"
ckc "E2 path/apply 两侧空数组 → 400"   "400" "$(st -H 'Content-Type: application/json' -d '{"system":[],"user":[],"dryRun":true}' $B/api/path/apply)"
ckc "E3 未知 /api 路径 → 404"          "404" "$(st $B/api/nope)"
ckc "E4 POST text/plain → 415"         "415" "$(st -H 'Content-Type: text/plain' -d '{}' $B/api/apply)"

kill $SRV 2>/dev/null; sleep 1
rm -rf "$ROOT"
echo
echo "==== 汇总：PASS $ok / FAIL $bad / SKIP $skip ===="
[ "$bad" -eq 0 ] || exit 1
