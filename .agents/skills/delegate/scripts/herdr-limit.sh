#!/usr/bin/env bash
# 대상들의 5시간 사용 한도 사용률을 상태줄에서 읽는다. 기준 이상이면 main이 새 위임을 멈추고 인계한다.
#
# 종료 코드: 0 기준 미만 / 5 모든 대상이 unknown / 6 한 대상이라도 기준 이상
# 사용법: herdr-limit.sh [--max <pct>] [<agent|pane|self>...]   (기본: --max 85, 대상 self impl review)
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

max="$(limit_max)"
if [ "${1:-}" = "--max" ]; then
  max="${2:?--max 값이 없다}"
  shift 2
fi
[ $# -gt 0 ] || set -- self impl review

require_herdr
known=0
over=0
for target in "$@"; do
  if pct="$(agent_limit_5h_pct "$target")"; then
    known=1
    echo "limit5h: $target ${pct}%"
    [ "$pct" -lt "$max" ] || over=1
  else
    echo "limit5h: $target unknown"
  fi
done
if [ "$over" = 1 ]; then
  echo "over: 5h 한도 ${max}% 이상"
  exit 6
fi
[ "$known" = 1 ] || exit 5
