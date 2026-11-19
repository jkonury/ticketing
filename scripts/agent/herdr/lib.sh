#!/usr/bin/env bash
# herdr 오케스트레이션 스크립트 공용 함수. source로만 사용한다.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
export STATE_DIR="$ROOT/build/orchestration"
export STATE_FILE="$STATE_DIR/panes.json"

die() {
  echo "error: $*" >&2
  exit 1
}

require_herdr() {
  [ "${HERDR_ENV:-}" = 1 ] || die "herdr 밖이다. 제어 명령을 내리지 않는다."
  command -v jq >/dev/null || die "jq가 필요하다."
}

# 이름으로 살아 있는 에이전트의 종류를 출력한다. 없으면 종료 코드 1.
agent_kind() {
  herdr agent get "$1" 2>/dev/null | jq -er '.result.agent.agent'
}

agent_pane() {
  herdr agent get "$1" 2>/dev/null | jq -er '.result.agent.pane_id'
}

agent_status() {
  herdr agent get "$1" 2>/dev/null | jq -er '.result.agent.agent_status'
}

agent_seq() {
  herdr agent get "$1" 2>/dev/null | jq -er '.result.agent.state_change_seq'
}

# 위임 직전의 state_change_seq. wait가 "위임 이후 상태가 바뀐 뒤의 idle"만 완료로 보게 한다.
baseline_file() {
  echo "$STATE_DIR/$1.baseline"
}
