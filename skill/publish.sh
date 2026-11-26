#!/usr/bin/env bash
# 把 skill/ 下的 skill 发布到 agent 的 skills 目录（默认 ~/.agents/skills）。
# 读取同目录 manifest.txt 的 [skills] / [retired] 两段。
# Windows 请用 Git Bash：C:\Program Files\Git\bin\bash.exe
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$SCRIPT_DIR/manifest.txt"
SKILLS_DIR="${HOME}/.agents/skills"
DRY_RUN=0
NO_REMOVE_LEGACY=0
LIST=0
declare -a SELECTED=()

usage() {
  cat <<'EOF'
用法: publish.sh [选项]

选项:
  --skills-dir DIR      目标 skills 目录（默认 ~/.agents/skills）
  --skill NAME          只发布指定 skill（可重复）
  --dry-run             只打印计划，不实际写文件
  --no-remove-legacy    不删除 manifest [retired] 中的旧 skill
  --list                列出清单后退出
  -h, --help            显示帮助
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skills-dir)        SKILLS_DIR="$2"; shift 2 ;;
    --skill)             SELECTED+=("$2"); shift 2 ;;
    --dry-run)           DRY_RUN=1; shift ;;
    --no-remove-legacy)  NO_REMOVE_LEGACY=1; shift ;;
    --list)              LIST=1; shift ;;
    -h|--help)           usage; exit 0 ;;
    *) printf '未知选项: %s\n' "$1" >&2; usage >&2; exit 1 ;;
  esac
done

if [[ ! -f "$MANIFEST" ]]; then
  printf '找不到 manifest: %s\n' "$MANIFEST" >&2
  exit 1
fi

declare -a SKILLS=() RETIRED=()
section=""
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"                 # 去掉 CRLF 的 \r
  line="${line#"${line%%[![:space:]]*}"}"  # 去掉行首空白
  line="${line%"${line##*[![:space:]]}"}"  # 去掉行尾空白
  [[ -z "$line" || "$line" == \#* ]] && continue
  if [[ "$line" =~ ^\[(.+)\]$ ]]; then
    section="${BASH_REMATCH[1]}"
    continue
  fi
  case "$section" in
    skills)  SKILLS+=("$line") ;;
    retired) RETIRED+=("$line") ;;
  esac
done < "$MANIFEST"

if [[ $LIST -eq 1 ]]; then
  printf '将发布（manifest [skills]）：\n'
  for n in "${SKILLS[@]}"; do printf '  - %s\n' "$n"; done
  printf '发布时移除（manifest [retired]）：\n'
  for n in "${RETIRED[@]}"; do printf '  - %s\n' "$n"; done
  printf '目标目录：%s\n' "$SKILLS_DIR"
  exit 0
fi

if [[ ${#SKILLS[@]} -eq 0 ]]; then
  printf 'manifest 中没有 [skills] 条目: %s\n' "$MANIFEST" >&2
  exit 1
fi

die() { printf '错误: %s\n' "$1" >&2; exit 1; }

valid_name() {
  [[ "$1" =~ ^[a-z][a-z0-9]*(-[a-z0-9]+){2,3}$ ]]
}

frontmatter_name() {
  local md="$1" first name
  IFS= read -r first < "$md" || true
  first="${first%$'\r'}"
  [[ "$first" == "---" ]] || return 1
  name="$(awk 'NR>1 && /^---[[:space:]]*$/ {exit} NR>1 && /^name:[ \t]/ {
      sub(/^name:[ \t]*/, ""); sub(/[[:space:]]+$/, ""); gsub(/["'"'"']/, ""); print; exit
    }' "$md")"
  [[ -n "$name" ]] || return 1
  printf '%s' "$name"
}

contains() {
  local needle="$1"; shift
  local item
  for item in "$@"; do [[ "$item" == "$needle" ]] && return 0; done
  return 1
}

# 选择要发布的 skill
declare -a TO_PUBLISH=()
if [[ ${#SELECTED[@]} -eq 0 ]]; then
  TO_PUBLISH=("${SKILLS[@]}")
else
  for n in "${SELECTED[@]}"; do
    contains "$n" "${SKILLS[@]}" || die "未知 skill: $n（可选项: ${SKILLS[*]}）"
    contains "$n" "${TO_PUBLISH[@]}" || TO_PUBLISH+=("$n")
  done
fi

# 校验源
for n in "${TO_PUBLISH[@]}"; do
  valid_name "$n" || die "目录名不符合命名格式（类别-作用大类-作用小类[-补充]）: $n"
  src="$SCRIPT_DIR/$n"
  [[ -d "$src" ]] || die "找不到 skill 目录: $src"
  md="$src/SKILL.md"
  [[ -f "$md" ]] || die "找不到 $md"
  fm="$(frontmatter_name "$md")" || die "SKILL.md 缺少 frontmatter/name: $md"
  [[ "$fm" == "$n" ]] || die "frontmatter name ($fm) 与目录名 ($n) 不一致: $md"
done

# 计算要移除的旧 skill
declare -a TO_REMOVE=()
if [[ $NO_REMOVE_LEGACY -eq 0 ]]; then
  for n in "${RETIRED[@]}"; do
    [[ "$n" =~ ^[a-z][a-z0-9]*(-[a-z0-9]+)+$ ]] || die "retired 目录名不合法: $n"
    contains "$n" "${TO_PUBLISH[@]}" && continue   # 安全：不删本次要发布的
    contains "$n" "${SKILLS[@]}" && continue
    TO_REMOVE+=("$n")
  done
fi

printf '目标目录：%s\n' "$SKILLS_DIR"
printf '发布：%d 个；移除旧 skill：%d 个\n' "${#TO_PUBLISH[@]}" "${#TO_REMOVE[@]}"
[[ $DRY_RUN -eq 1 ]] && printf '[DRY-RUN] 以下操作不会实际执行\n'

if [[ $DRY_RUN -eq 0 ]]; then
  mkdir -p "$SKILLS_DIR"
fi

for n in "${TO_PUBLISH[@]}"; do
  src="$SCRIPT_DIR/$n"
  dest="$SKILLS_DIR/$n"
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '[DRY-RUN] 发布 %s -> %s\n' "$n" "$dest"
    continue
  fi
  rm -rf "$dest"
  mkdir -p "$dest"
  cp -R "$src/." "$dest/"
  printf '[OK] 发布 %s\n' "$n"
done

for n in "${TO_REMOVE[@]}"; do
  dest="$SKILLS_DIR/$n"
  if [[ ! -e "$dest" ]]; then
    [[ $DRY_RUN -eq 0 ]] && printf '[SKIP] 旧 skill 不存在: %s\n' "$n"
    continue
  fi
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '[DRY-RUN] 移除旧 skill %s\n' "$dest"
    continue
  fi
  rm -rf "$dest"
  printf '[OK] 移除旧 skill %s\n' "$n"
done

printf '完成。\n'
