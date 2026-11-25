#!/usr/bin/env bash
# 에이전트(또는 main 자신)의 컨텍스트 사용률을 상태줄에서 읽는다. 모델은 /context·/status를 직접 실행할 수 없다.
#
# 종료 코드: 0 정상 / 5 사용률을 읽지 못함(unknown) / 6 --max 이상(압축 필요)
# 사용법: herdr-context.sh <agent|pane|self> [--max <pct>]
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -ge 1 ] || die "사용법: herdr-context.sh <agent|pane|self> [--max <pct>]"
target="$1"
shift
max=""
if [ "${1:-}" = "--max" ]; then max="${2:?--max 값이 없다}"; fi

require_herdr
if ! pct="$(agent_context_pct "$target")"; then
  echo "unknown: $target"
  exit 5
fi
echo "context: $target ${pct}%"
if [ -n "$max" ] && [ "$pct" -ge "$max" ]; then
  exit 6
fi
