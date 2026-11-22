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

# 종류별 서브 세션 기본 인자. 추론 강도는 medium이 기본이고 어려운 task만 high를 쓴다.
# 모델은 세 번째 인자로 바꿀 수 있다(비우면 기본 모델).
agent_args() { # <kind> <effort> [model]
  local kind="$1" effort="${2:-medium}" model="${3:-}"
  case "$effort" in
    medium|high) ;;
    *) die "추론 강도는 medium 또는 high다: $effort" ;;
  esac
  case "$kind" in
    claude) echo "--model ${model:-sonnet} --effort $effort" ;;
    # 업데이트 안내 화면이 재시작마다 시작을 막으므로 끈다.
    codex) echo "-m ${model:-gpt-6.2-sol} -c model_reasoning_effort=$effort -c check_for_update_on_startup=false" ;;
    *) die "기본 인자가 없는 에이전트 종류다: $kind" ;;
  esac
}

# herdr-up이 기록한 에이전트의 지정 모델(없으면 빈 문자열). 재시작 후에도 같은 모델을 쓰기 위해 쓴다.
agent_model() { # <name>
  [ -f "$STATE_FILE" ] || return 0
  jq -r --arg n "$1" '[.impl, .review] | map(select(.name == $n) | .model // "") | first // ""' "$STATE_FILE" 2>/dev/null || true
}

# 에이전트를 같은 pane에서 종료하고 새 세션으로 다시 시작한다. herdr에 agent stop이 없어 /exit로 끝낸다.
restart_agent() { # <name> <effort>
  local name="$1" effort="$2" kind pane i
  kind="$(agent_kind "$name")" || die "에이전트 $name 를 찾지 못했다."
  pane="$(agent_pane "$name")" || die "에이전트 $name 의 pane을 찾지 못했다."
  herdr agent prompt "$name" "/exit" >/dev/null || die "에이전트 $name 에 종료 명령을 보내지 못했다."
  # 종료 직후에는 종류 필드가 먼저 비고 이름 등록은 남아 있으므로 agent_not_found가 될 때까지 기다린다.
  for i in $(seq 1 60); do
    herdr agent get "$name" >/dev/null 2>&1 || break
    [ "$i" -lt 60 ] || die "에이전트 $name 가 30초 안에 종료되지 않았다."
    sleep 0.5
  done
  # shellcheck disable=SC2046
  herdr agent start "$name" --kind "$kind" --pane "$pane" -- $(agent_args "$kind" "$effort" "$(agent_model "$name")") >/dev/null ||
    die "에이전트 $name 를 다시 시작하지 못했다(pane=$pane)."
  echo "restarted: $name ($kind, effort=$effort)"
}

# 에이전트 상태줄에서 컨텍스트 사용률(정수 %)을 읽는다. 읽지 못하면 종료 코드 1.
# Claude는 statusline의 "🧠 N%", Codex는 하단의 "Context N% used"를 쓴다. 대상은 이름·pane ID·self.
agent_context_pct() { # <agent|pane|self> [probe]
  local target="$1" probe="${2:-}" screen pct kind cmd left
  [ "$target" = self ] && target="${HERDR_PANE_ID:?HERDR_PANE_ID가 없다}"
  screen="$(herdr agent read "$target" --source visible --lines 20 2>/dev/null)" || return 1
  pct="$(printf '%s\n' "$screen" | sed -nE 's/.*🧠 *([0-9]+)%.*/\1/p; s/.*Context ([0-9]+)% used.*/\1/p' | tail -n 1)"
  # 상태줄에 사용률이 없으면 idle인 대상에 /context(claude)·/status(codex)를 입력해 출력에서 읽는다. 대화에 출력이 남는다.
  # main 자신은 턴 중에 입력이 안 되므로 idle을 보장하는 호출(릴레이)만 probe로 허용한다.
  if [ -z "$pct" ] && { [ "$target" != "${HERDR_PANE_ID:-}" ] || [ "$probe" = probe ]; }; then
    kind="$(herdr agent get "$target" 2>/dev/null | jq -er '
      .result.agent | select(.agent_status == "idle" or .agent_status == "done") | .agent
    ')" || return 1
    case "$kind" in
      claude) cmd=/context ;;
      codex) cmd=/status ;;
      *) return 1 ;;
    esac
    herdr agent prompt "$target" "$cmd" >/dev/null 2>&1 || return 1
    sleep 3
    screen="$(herdr agent read "$target" --source visible --lines 80 2>/dev/null)" || return 1
    if [ "$kind" = claude ]; then
      pct="$(printf '%s\n' "$screen" | sed -nE 's/.*[0-9.]+k?\/[0-9.]+[km] tokens \(([0-9]+)%\).*/\1/p' | tail -n 1)"
    else
      left="$(printf '%s\n' "$screen" | sed -nE 's/.*Context window: *([0-9]+)% left.*/\1/p' | tail -n 1)"
      [ -n "$left" ] && pct=$((100 - left))
    fi
  fi
  [ -n "$pct" ] || return 1
  echo "$pct"
}

# 상태줄에서 5시간 사용 한도의 사용률(정수 %)을 읽는다. 읽지 못하면 종료 코드 1.
# Claude statusline은 "5h <막대> N%"(사용률), Codex 하단은 "5h N% left"(남은 비율)다.
# Claude는 응답을 한 번 받기 전까지 상태줄에 한도가 없을 수 있다. 같은 계정의 Claude pane끼리 한도를 공유한다.
agent_limit_5h_pct() { # <agent|pane|self>
  local target="$1" screen pct left
  [ "$target" = self ] && target="${HERDR_PANE_ID:?HERDR_PANE_ID가 없다}"
  screen="$(herdr agent read "$target" --source visible --lines 20 2>/dev/null)" || return 1
  pct="$(printf '%s\n' "$screen" | sed -nE 's/.*5h [█░]+ ([0-9]+)%.*/\1/p' | tail -n 1)"
  if [ -z "$pct" ]; then
    left="$(printf '%s\n' "$screen" | sed -nE 's/.*5h ([0-9]+)% left.*/\1/p' | tail -n 1)"
    [ -n "$left" ] && pct=$((100 - left))
  fi
  [ -n "$pct" ] || return 1
  echo "$pct"
}

# 5시간 한도 중단 기준(%). HERDR_LIMIT_MAX로 바꿀 수 있다.
limit_max() {
  echo "${HERDR_LIMIT_MAX:-85}"
}
