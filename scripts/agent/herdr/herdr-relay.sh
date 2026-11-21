#!/usr/bin/env bash
# main 세션의 자가 압축 릴레이. main은 자기 턴 안에서 /compact·/clear를 실행할 수 없으므로,
# main이 이 스크립트를 백그라운드로 실행하고 턴을 끝내면 idle이 된 뒤 pane에 명령을 넣는다.
#
# 순서: main idle 대기 → /compact(또는 /clear) → idle 대기 → 재개 프롬프트 → 사용률 기록
# compact 후에도 --max 이상이면 /clear 후 progress.md로 재개한다.
# 종료 코드: 0 완료 / 3 blocked(전송하지 않음) / 4 시간 초과
#
# 사용법: herdr-relay.sh --resume <progress.md> [--mode compact|clear] [--max <pct>] [--log <context.log>] [--timeout ms]
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  die "사용법: herdr-relay.sh --resume <progress.md> [--mode compact|clear] [--max <pct>] [--log <context.log>] [--timeout ms]"
}

mode=compact
max=25
resume=""
log=""
timeout_ms=600000
while [ $# -gt 0 ]; do
  case "$1" in
    --resume) resume="${2:-}"; shift 2 ;;
    --mode) mode="${2:-}"; shift 2 ;;
    --max) max="${2:-}"; shift 2 ;;
    --log) log="${2:-}"; shift 2 ;;
    --timeout) timeout_ms="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$resume" ] || usage
case "$mode" in compact|clear) ;; *) usage ;; esac
[ -f "$resume" ] || die "progress 파일이 없다: $resume"
resume="$(cd "$(dirname "$resume")" && pwd)/$(basename "$resume")"

require_herdr
self="${HERDR_PANE_ID:?HERDR_PANE_ID가 없다}"
deadline=$(( $(date +%s) + timeout_ms / 1000 ))

record() { # <단계>
  [ -n "$log" ] || return 0
  mkdir -p "$(dirname "$log")"
  echo "$(date '+%F %T') relay $1 context=$(agent_context_pct self probe || echo unknown)%" >>"$log"
}

# 턴이 끝나 main이 idle이 될 때까지 기다린다. 호출 직후에는 아직 working으로 바뀌기 전일 수 있어 먼저 working 전이를 잠시 기다린다.
wait_idle() { # <settle-secs>
  local status remain_ms
  sleep "$1"
  while :; do
    [ "$(date +%s)" -lt "$deadline" ] || { echo "timeout: relay"; exit 4; }
    remain_ms=$(( (deadline - $(date +%s)) * 1000 ))
    herdr agent wait "$self" --timeout "$remain_ms" >/dev/null 2>&1 || true
    status="$(agent_status "$self")" || die "main pane $self 를 찾지 못했다."
    case "$status" in
      idle|done) sleep 2; [ "$(agent_status "$self")" = working ] || return 0 ;;
      blocked) echo "blocked: main (사용자 응답 전에는 입력하지 않는다)"; exit 3 ;;
      *) sleep 3 ;;
    esac
  done
}

send() { # <text>
  herdr agent prompt "$self" "$1" >/dev/null || die "main 에 입력을 보내지 못했다."
}

# main이 이 스크립트를 띄운 뒤 턴을 마칠 시간을 준다.
wait_idle 5
record before
send "/$mode"
wait_idle 5
record "after-$mode"

if [ "$mode" = compact ]; then
  pct="$(agent_context_pct self probe || echo 100)"
  if [ "$pct" -ge "$max" ]; then
    send "/clear"
    wait_idle 3
    record after-clear
  fi
fi

send "$resume 를 읽고 기록된 다음 행동부터 이어서 진행해라."
echo "relayed: main <- $resume"
