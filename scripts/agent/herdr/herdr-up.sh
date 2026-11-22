#!/usr/bin/env bash
# 현재 탭에 구현자(impl)와 리뷰어(review) 에이전트 pane을 만든다. 이미 있으면 재사용한다(멱등).
# 구현자와 리뷰어는 항상 다른 종류이고, 구현자 기본값은 메인 세션과 같은 종류다.
#
# 사용법: herdr-up.sh [--impl-kind K] [--review-kind K] [--impl-name N] [--review-name N]
#                     [--impl-model M] [--review-model M] [--impl-args "..."] [--review-args "..."]
#   --*-model은 기본 모델만 바꾼다(추론 강도 등 나머지 기본 인자는 유지). 재시작 후에도 같은 모델을 쓴다.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

impl_name=impl
review_name=review
impl_kind=""
review_kind=""
impl_model=""
review_model=""
impl_args=""
review_args=""
impl_args_set=0
review_args_set=0

while [ $# -gt 0 ]; do
  case "$1" in
    --impl-kind) impl_kind="${2:?}"; shift 2 ;;
    --review-kind) review_kind="${2:?}"; shift 2 ;;
    --impl-name) impl_name="${2:?}"; shift 2 ;;
    --review-name) review_name="${2:?}"; shift 2 ;;
    --impl-model) impl_model="${2:?}"; shift 2 ;;
    --review-model) review_model="${2:?}"; shift 2 ;;
    --impl-args) impl_args="${2-}"; impl_args_set=1; shift 2 ;;
    --review-args) review_args="${2-}"; review_args_set=1; shift 2 ;;
    *) die "알 수 없는 인자: $1" ;;
  esac
done

require_herdr

other_kind() {
  case "$1" in
    claude) echo codex ;;
    codex) echo claude ;;
    *) return 1 ;;
  esac
}

# 종류 결정: 이미 살아 있는 에이전트 > 인자 > 메인 세션 종류
existing_impl="$(agent_kind "$impl_name" || true)"
existing_review="$(agent_kind "$review_name" || true)"

if [ -z "$impl_kind" ]; then
  if [ -n "$existing_impl" ]; then
    impl_kind="$existing_impl"
  else
    main_kind="$(herdr pane current --current | jq -er '.result.pane.agent' || true)"
    case "$main_kind" in
      claude|codex) impl_kind="$main_kind" ;;
      *) die "메인 세션 종류를 감지하지 못했다(${main_kind:-없음}). --impl-kind claude|codex를 지정한다." ;;
    esac
  fi
fi
if [ -z "$review_kind" ]; then
  if [ -n "$existing_review" ]; then
    review_kind="$existing_review"
  else
    review_kind="$(other_kind "$impl_kind")" || die "impl 종류($impl_kind)의 반대 종류를 정할 수 없다. --review-kind를 지정한다."
  fi
fi

[ "$impl_kind" != "$review_kind" ] || die "impl과 review는 서로 다른 종류여야 한다(둘 다 $impl_kind)."
if [ -n "$existing_impl" ] && [ "$existing_impl" != "$impl_kind" ]; then
  die "살아 있는 $impl_name 의 종류($existing_impl)가 요청($impl_kind)과 다르다. 직접 정리한 뒤 다시 실행한다."
fi
if [ -n "$existing_review" ] && [ "$existing_review" != "$review_kind" ]; then
  die "살아 있는 $review_name 의 종류($existing_review)가 요청($review_kind)과 다르다. 직접 정리한 뒤 다시 실행한다."
fi

# 종류별 기본 인자: claude는 Sonnet, codex는 gpt-6.2-sol, 추론 강도는 medium
if [ "$impl_args_set" = 0 ]; then impl_args="$(agent_args "$impl_kind" medium "$impl_model")"; fi
if [ "$review_args_set" = 0 ]; then review_args="$(agent_args "$review_kind" medium "$review_model")"; fi

split_pane() { # <pane|--current> <direction> -> 새 pane id
  local target="$1" dir="$2" args=()
  if [ "$target" = "--current" ]; then args+=(--current); else args+=("$target"); fi
  herdr pane split "${args[@]}" --direction "$dir" --cwd "$PWD" --no-focus | jq -er '.result.pane.pane_id'
}

start_agent() { # <name> <kind> <pane> <args>
  local name="$1" kind="$2" pane="$3" extra="$4"
  if [ -n "$extra" ]; then
    # shellcheck disable=SC2086
    herdr agent start "$name" --kind "$kind" --pane "$pane" -- $extra >/dev/null
  else
    herdr agent start "$name" --kind "$kind" --pane "$pane" >/dev/null
  fi
}

impl_pane=""
review_pane=""
created=()

if [ -z "$existing_impl" ]; then
  impl_pane="$(split_pane --current right)"
  created+=("$impl_pane")
  start_agent "$impl_name" "$impl_kind" "$impl_pane" "$impl_args"
else
  impl_pane="$(agent_pane "$impl_name")"
fi

if [ -z "$existing_review" ]; then
  review_pane="$(split_pane "$impl_pane" down)"
  created+=("$review_pane")
  start_agent "$review_name" "$review_kind" "$review_pane" "$review_args"
else
  review_pane="$(agent_pane "$review_name")"
fi

# 모델을 다시 지정하지 않았으면 이전 기록을 유지한다(재사용한 에이전트가 재시작 후에도 같은 모델을 쓰도록).
[ -n "$impl_model" ] || impl_model="$(agent_model "$impl_name")"
[ -n "$review_model" ] || review_model="$(agent_model "$review_name")"

mkdir -p "$STATE_DIR"
# 이전 실행에서 만든 pane 기록을 보존하고 이번에 새로 만든 pane만 추가한다(down이 내가 만든 pane만 닫도록).
prev_created='[]'
[ -f "$STATE_FILE" ] && prev_created="$(jq -c '.created // []' "$STATE_FILE")"
jq -n \
  --arg impl_name "$impl_name" --arg impl_kind "$impl_kind" --arg impl_pane "$impl_pane" \
  --arg review_name "$review_name" --arg review_kind "$review_kind" --arg review_pane "$review_pane" \
  --arg impl_model "$impl_model" --arg review_model "$review_model" \
  --argjson prev "$prev_created" --argjson new "$(printf '%s\n' "${created[@]:-}" | jq -R . | jq -s 'map(select(length>0))')" \
  '{impl:{name:$impl_name,kind:$impl_kind,pane:$impl_pane,model:$impl_model},
    review:{name:$review_name,kind:$review_kind,pane:$review_pane,model:$review_model},
    created:($prev+$new|unique)}' >"$STATE_FILE"

echo "impl:   $impl_name ($impl_kind) pane=$impl_pane"
echo "review: $review_name ($review_kind) pane=$review_pane"
echo "state:  $STATE_FILE"
