#!/usr/bin/env bash
# 작업자에게 지시서 경로만 담은 짧은 프롬프트를 보낸다. 완료 대기는 herdr-wait.sh가 맡는다.
#
# 사용법: herdr-delegate.sh [--task <task-dir>] [--effort medium|high] <agent> <brief.md> [<report.md>]
#   task-dir은 실행 디렉터리 바로 아래의 task 디렉터리다.
#   --task를 주면 task에서 처음 위임받는 에이전트를 새 세션으로 다시 시작한다(--effort 기본 medium).
#   report 경로를 생략하면 brief와 같은 디렉터리의 report.md(review는 review.md)를 쓴다.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  die "사용법: herdr-delegate.sh [--task <task-dir>] [--effort medium|high] <agent> <brief.md> [<report.md>]"
}

# 상태 파일을 임시 파일로 쓴 뒤 교체한다. jq 인자는 그대로 전달한다(필터와 입력 파일 포함).
write_state() { # <state> <jq-args...>
  local state="$1" temp
  shift
  temp="$(mktemp "${state}.XXXXXXXX")" || die "task 상태 임시 파일을 만들지 못했다: $state"
  if ! jq "$@" >"$temp" || ! mv -f -- "$temp" "$state"; then
    rm -f -- "$temp"
    die "task 상태를 저장하지 못했다: $state"
  fi
}

guard_task() {
  local task_dir="$1" state head active_task start_head parents changes
  state="$(dirname -- "$task_dir")/active-task.json"
  head="$(git -C "$ROOT" rev-parse --verify HEAD)" || die "현재 HEAD를 읽지 못했다."

  if [ -e "$state" ] || [ -L "$state" ]; then
    active_task="$(jq -er '.task_dir | strings | select(length > 0)' "$state")" || die "task 상태 파일을 읽지 못했다: $state"
    start_head="$(jq -er '.start_head | strings | select(test("^([a-f0-9]{40}|[a-f0-9]{64})$"))' "$state")" || die "task 시작 HEAD를 읽지 못했다: $state"
    if [ "$task_dir" = "$active_task" ]; then
      [ "$head" = "$start_head" ] || die "현재 task 시작 이후 HEAD가 바뀌었다: $active_task"
      return
    fi

    parents="$(git -C "$ROOT" rev-list --parents -n 1 "$head")" || die "현재 HEAD의 부모를 읽지 못했다."
    [ "$parents" = "$head $start_head" ] || die "다음 task를 시작하려면 현재 task의 일반 커밋 한 개가 필요하다: $active_task"
  fi

  changes="$(git -C "$ROOT" status --porcelain=v1 --untracked-files=all --ignore-submodules=none)" || die "작업 트리 상태를 읽지 못했다."
  [ -z "$changes" ] || die "새 task를 시작하려면 staged·unstaged·untracked 변경이 없어야 한다."

  write_state "$state" -n --arg task_dir "$task_dir" --arg start_head "$head" '{task_dir: $task_dir, start_head: $start_head, agents: []}'
}

# task마다 서브 세션을 새로 시작한다. 같은 task의 수정·재리뷰는 기존 세션을 이어 쓴다.
fresh_session() { # <task-dir> <agent> <effort>
  local state
  state="$(dirname -- "$1")/active-task.json"
  if jq -e --arg agent "$2" '(.agents // []) | index($agent)' "$state" >/dev/null; then
    return
  fi
  restart_agent "$2" "$3"
  write_state "$state" --arg agent "$2" '.agents = ((.agents // []) + [$agent] | unique)' "$state"
}

task_dir=""
effort=medium
while [ $# -gt 0 ]; do
  case "$1" in
    --task)
      [ -n "${2:-}" ] || usage
      task_dir="$(cd -P -- "$2" && pwd)" || die "task 디렉터리가 없다: $2"
      shift 2
      ;;
    --effort)
      case "${2:-}" in medium|high) effort="$2" ;; *) usage ;; esac
      shift 2
      ;;
    --) shift; break ;;
    -*) usage ;;
    *) break ;;
  esac
done
if [ $# -lt 2 ] || [ $# -gt 3 ]; then
  usage
fi
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
if [ -n "$task_dir" ]; then
  guard_task "$task_dir"
  fresh_session "$task_dir" "$agent" "$effort"
fi
mkdir -p "$(dirname "$report")"
rm -f "$report"

mkdir -p "$STATE_DIR"
agent_seq "$agent" >"$(baseline_file "$agent")"

if [ -n "$task_dir" ]; then
  prompt="$brief 파일을 읽고 그 지시대로 수행해라."
else
  prompt="$brief 파일을 읽고 그 지시대로 수행해라. 커밋은 하지 않는다. 끝나면 $report 에 결과를 쓰고 한 줄로만 답해라."
fi
# --wait를 붙이지 않는다. 대기는 herdr-wait.sh로 한다.
herdr agent prompt "$agent" "$prompt" >/dev/null
echo "delegated: $agent <- $brief"
echo "report:    $report"
