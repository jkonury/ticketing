#!/usr/bin/env bash
# 다른 모델의 에이전트 CLI를 읽기 전용 비대화 모드로 한 번 호출해 리뷰 결과를 파일로 받는다.
# 사용법: run-reviewer.sh <claude|codex> <프롬프트 파일> <결과 파일> <기준SHA...검토SHA> [제한 시간(초, 기본 900)]
# 저장소 루트에서 실행하고 build/review-diff의 실행 디렉터리에 결과를 보관한다.
# 종료 코드: 0 성공, 1 호출 실패·시간 초과·빈 결과, 2 사용법 오류·CLI 없음
set -uo pipefail

reviewer="${1:-}" prompt_file="${2:-}" out_file="${3:-}" range="${4:-}" limit="${5:-900}"

if [ -z "$reviewer" ] || [ ! -f "$prompt_file" ] || [ -z "$out_file" ] ||
   [[ ! "$range" =~ ^[[:xdigit:]]{40}\.\.\.[[:xdigit:]]{40}$ ]]; then
  echo "사용법: $0 <claude|codex> <프롬프트 파일> <결과 파일> <기준SHA...검토SHA> [제한 시간(초)]" >&2
  exit 2
fi
command -v "$reviewer" >/dev/null 2>&1 || { echo "CLI를 찾을 수 없음: $reviewer" >&2; exit 2; }
command -v timeout >/dev/null 2>&1 || { echo "timeout 명령이 필요하다" >&2; exit 2; }

review_root="$(pwd -P)/build/review-diff"
out_dir=$(cd -- "$(dirname -- "$out_file")" && pwd -P) || exit 2
if [[ "$out_dir" != "$review_root/"* ]]; then
  echo "결과 파일은 $review_root 아래 실행 디렉터리에 둔다." >&2
  exit 2
fi
out_file="$out_dir/$(basename -- "$out_file")"
log_file="${out_file}.log"
if [[ -L "$out_file" || -L "$log_file" ]]; then
  echo "결과 파일과 오류 로그에는 심볼릭 링크를 사용할 수 없다." >&2
  exit 2
fi
umask 077
: > "$out_file" || exit 2

case "$reviewer" in
  claude)
    review_dir=$(mktemp -d) || exit 1
    trap 'rm -f -- "$review_dir/changes.diff"; rmdir -- "$review_dir"' EXIT
    git diff --no-color --no-ext-diff --no-textconv "$range" -- >"$review_dir/changes.diff" 2>"$log_file" || {
      echo "리뷰 diff 생성 실패. 로그: $log_file" >&2
      exit 1
    }
    if [ ! -s "$review_dir/changes.diff" ]; then
      echo "리뷰할 변경 없음: $range" >&2
      exit 1
    fi
    timeout "$limit" claude -p \
      --permission-mode dontAsk \
      --tools 'Read,Grep,Glob' \
      --allowedTools 'Read,Grep,Glob' \
      --strict-mcp-config \
      --settings '{"disableAllHooks":true}' \
      --add-dir "$review_dir" \
      --append-system-prompt "리뷰 범위 $range의 전체 diff는 $review_dir/changes.diff 에 있다. Read로 확인하고 길면 offset을 지정해 끝까지 읽는다. 저장소 파일은 문맥 확인에 사용한다. 이 세션에는 파일 조회 도구만 있으며 테스트 결과는 직접 재현하지 않는다." \
      --no-session-persistence \
      <"$prompt_file" >"$out_file" 2>"$log_file"
    ;;
  codex)
    # read-only 샌드박스라 파일을 쓰거나 네트워크를 쓰는 명령은 실패한다.
    timeout "$limit" codex exec \
      --sandbox read-only \
      --ephemeral \
      --cd "$PWD" \
      --output-last-message "$out_file" \
      - <"$prompt_file" >/dev/null 2>"$log_file"
    ;;
  *)
    echo "지원하지 않는 리뷰어: $reviewer (claude 또는 codex)" >&2
    exit 2
    ;;
esac
status=$?

if [ "$status" -ne 0 ]; then
  echo "리뷰어 호출 실패($reviewer, 종료 코드 $status). 로그: $log_file" >&2
  exit 1
fi
if [ ! -s "$out_file" ]; then
  echo "리뷰어가 빈 결과를 반환했다($reviewer)" >&2
  exit 1
fi
echo "$out_file"
