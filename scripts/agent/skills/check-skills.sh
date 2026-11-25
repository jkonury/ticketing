#!/usr/bin/env bash
# 공용 skill 원본(.agents/skills)과 Claude Code용 symlink(.claude/skills)의 짝, frontmatter, 상대 링크를 검사한다.
# 사용법: scripts/agent/skills/check-skills.sh
# 종료 코드: 0 정상, 1 불일치
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)" || exit 2
cd "$ROOT" || exit 2

SRC=.agents/skills
LINKS=.claude/skills
fail=0
err() { echo "$1"; fail=1; }

frontmatter_value() { # $1 파일, $2 키: 첫 frontmatter 블록에서 값만 출력
  awk -v key="$2" 'NR == 1 && $0 != "---" { exit } NR > 1 && $0 == "---" { exit }
    NR > 1 && index($0, key ":") == 1 { sub("^" key ":[ ]*", ""); print; exit }' "$1"
}

for dir in "$SRC"/*/; do
  name="$(basename "$dir")"
  file="$dir/SKILL.md"
  [ -f "$file" ] || { err "SKILL.md 없음: $dir"; continue; }
  [ "$(frontmatter_value "$file" name)" = "$name" ] || err "name이 디렉터리명과 다름: $file"
  [ -n "$(frontmatter_value "$file" description)" ] || err "description 없음: $file"
  link="$LINKS/$name"
  if [ ! -L "$link" ]; then
    err "symlink 없음: $link -> ../../$SRC/$name"
  elif [ "$(readlink "$link")" != "../../$SRC/$name" ]; then
    err "symlink 대상이 다름: $link -> $(readlink "$link")"
  fi
  while IFS= read -r target; do
    target="${target%%#*}"
    [ -z "$target" ] || [ -e "$dir/$target" ] || err "링크 대상 없음: $file -> $target"
  done < <(grep -oE '\]\([^)]+\)' "$file" | sed -E 's/^\]\(//; s/\)$//' | grep -vE '^(https?:|mailto:|#)')
done

for entry in "$LINKS"/*; do
  [ -e "$entry" ] || [ -L "$entry" ] || continue
  name="$(basename "$entry")"
  [ -d "$SRC/$name" ] || err "원본 없는 Claude skill: $entry (원본은 $SRC/$name 에 둔다)"
done

[ "$fail" = 0 ] && echo "skill 검사 통과"
exit "$fail"
