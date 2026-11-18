#!/usr/bin/env bash
# 작업자가 끝날 때까지 기다린다. 메인 세션이 run_in_background로 실행해 완료 알림을 받는다.
#
# 종료 코드: 0 완료 / 3 blocked(승인·질문 대기) / 4 시간 초과
# --until을 주지 않아 idle·done·blocked 모두 매칭한다. idle만 기다리면 done을 놓친다.
# 위임 직후에는 아직 idle일 수 있으므로, herdr-delegate.sh가 남긴 기준 seq 이후에 상태가 바뀐 뒤의 idle/done만 완료로 본다.
# idle/done이어도 작업자의 백그라운드 셸·모니터가 돌고 있으면 끝난 것이 아니므로 다시 기다린다.
#
# 사용법: herdr-wait.sh <agent> [--timeout ms]
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -ge 1 ] || die "사용법: herdr-wait.sh <agent> [--timeout ms]"
agent="$1"
shift
timeout_ms=3600000
if [ "${1:-}" = "--timeout" ]; then timeout_ms="${2:?}"; fi

require_herdr
deadline=$(( $(date +%s) + timeout_ms / 1000 ))
baseline=-1
[ -f "$(baseline_file "$agent")" ] && baseline="$(cat "$(baseline_file "$agent")")"
busy_re='shell still running|monitors? still running|shells? still running'

while :; do
  now="$(date +%s)"
  [ "$now" -lt "$deadline" ] || { echo "timeout: $agent"; exit 4; }
  remain_ms=$(( (deadline - now) * 1000 ))

  herdr agent wait "$agent" --timeout "$remain_ms" >/dev/null 2>&1 || true
  status="$(agent_status "$agent")" || die "에이전트 $agent 를 찾지 못했다."

  case "$status" in
    blocked)
      echo "blocked: $agent"
      herdr agent read "$agent" --source visible --lines 40 || true
      exit 3
      ;;
    idle|done)
      seq="$(agent_seq "$agent" || echo 0)"
      if [ "$seq" -le "$baseline" ]; then
        # 위임 이후 아직 working으로 바뀐 적이 없다. 프롬프트 반영 전일 수 있다.
        sleep 3
        continue
      fi
      screen="$(herdr agent read "$agent" --source visible --lines 60 || true)"
      if printf '%s' "$screen" | grep -Eiq "$busy_re"; then
        sleep 20
        continue
      fi
      echo "done: $agent ($status)"
      exit 0
      ;;
    *)
      # working/unknown: 완료의 증거가 아니다. 잠시 후 다시 대기한다.
      sleep 5
      ;;
  esac
done
