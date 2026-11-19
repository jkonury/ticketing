#!/usr/bin/env bash
# 문서의 백틱 경로를 저장소 루트 기준으로 검사한다. 링크는 check-docs.sh가 검사한다.
# 사용법: scripts/agent/docs/check-doc-refs.sh
# 설명용 경로는 아래 SKIP_PATHS에 추가한다. Exit code: 0 정상, 1 불일치, 2 환경 오류.

set -uo pipefail
shopt -s nullglob globstar

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)" || exit 2
cd "$ROOT" || exit 2

# 생성물이나 설명용으로만 쓰는 경로(정규식, 전체 일치)
SKIP_PATHS=(
  'build/.*'
  'core/build/.*'              # Gradle이 생성하는 테스트 결과 등
  '.*node_modules/.*'          # 설치해야 생기는 경로
  'web/\.env\.local'          # 로컬 개발 환경 설정
)

fail=0

mapfile -t DOCS < <(git ls-files 'docs/*.md' '*AGENTS.md' README.md)

# 1. 백틱 경로 존재 확인
while IFS=: read -r file line path; do
  path="${path%%:[0-9]*}"; path="${path%[.,]}"
  case "$path" in *'<'*|*'['*|*'$'*|*'{'*|*'...'*) continue ;; esac
  skip=0
  for re in "${SKIP_PATHS[@]}"; do [[ "$path" =~ ^$re$ ]] && { skip=1; break; }; done
  [ "$skip" = 1 ] && continue
  found=0
  for m in $path; do [ -e "$m" ] && found=1; done
  [ "$found" = 0 ] && { echo "경로 없음: $file:$line \`$path\`"; fail=1; }
done < <(grep -noE '`(\./)?((core|core-batch|realtime|gateway|fake-pg|web|scripts|docs|gradle|build-logic)/[^` ]+)`' "${DOCS[@]}" | sed -E 's/`//g; s#:\./#:#' | sed -E 's#^([^:]+):([0-9]+):#\1:\2:#')

[ "$fail" = 0 ] && echo "문서 참조 검사 통과"
exit "$fail"
