#!/usr/bin/env bash
# 로컬 마크다운 링크·앵커와 백틱 경로를 검사한다. 외부 URL은 검사하지 않는다.
# 사용법: scripts/agent/docs/check-docs.sh [링크 검사 대상]...
# 기본 대상은 docs, README, CLAUDE와 저장소의 모든 AGENTS.md다.
# 종료 코드: 0 정상, 실패 시 먼저 실패한 검사의 코드.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)" || exit 2
cd "$ROOT" || exit 2
command -v lychee >/dev/null 2>&1 || { echo 'ERROR: lychee가 필요합니다 (https://lychee.cli.rs).' >&2; exit 2; }

if [ "$#" -eq 0 ]; then
  set -- docs README.md CLAUDE.md
  while IFS= read -r -d '' file; do
    [ -f "$file" ] && set -- "$@" "$file"
  done < <(git ls-files -z --cached --others --exclude-standard -- '*AGENTS.md')
fi

rc=0
"$ROOT/scripts/agent/test/run-summary.sh" -- lychee --config "$ROOT/lychee.toml" "$@" || rc=$?
"$ROOT/scripts/agent/test/run-summary.sh" -- "$ROOT/scripts/agent/docs/check-doc-refs.sh" || { result=$?; [ "$rc" -ne 0 ] || rc=$result; }
exit "$rc"
