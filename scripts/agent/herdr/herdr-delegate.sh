#!/usr/bin/env bash
# 작업자에게 지시서 경로만 담은 짧은 프롬프트를 보낸다. 완료 대기는 herdr-wait.sh가 맡는다.
#
# 사용법: herdr-delegate.sh <agent> <brief.md> [<report.md>]
#   report 경로를 생략하면 brief와 같은 디렉터리의 report.md(review는 review.md)를 쓴다.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -ge 2 ] || die "사용법: herdr-delegate.sh <agent> <brief.md> [<report.md>]"
agent="$1"
brief="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
[ -f "$brief" ] || die "지시서가 없다: $2"

require_herdr
status="$(agent_status "$agent")" || die "에이전트 $agent 를 찾지 못했다. herdr-up.sh를 먼저 실행한다."
case "$status" in
  idle|done) ;;
  *) die "에이전트 $agent 상태가 $status 이다. 입력 가능한 상태(idle/done)가 아니다." ;;
esac

report="${3:-}"
if [ -z "$report" ]; then
  report="$(dirname "$brief")/report.md"
  [ "$agent" = review ] && report="$(dirname "$brief")/review.md"
fi
mkdir -p "$(dirname "$report")"
rm -f "$report"

mkdir -p "$STATE_DIR"
agent_seq "$agent" >"$(baseline_file "$agent")"

prompt="$brief 파일을 읽고 그 지시대로 수행해라. 커밋은 하지 않는다. 끝나면 $report 에 결과를 쓰고 한 줄로만 답해라."
# --wait를 붙이지 않는다. 대기는 herdr-wait.sh로 한다.
herdr agent prompt "$agent" "$prompt" >/dev/null
echo "delegated: $agent <- $brief"
echo "report:    $report"
