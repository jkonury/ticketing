#!/usr/bin/env bash
# 다른 모델의 에이전트 CLI를 읽기 전용 비대화 모드로 한 번 호출해 리뷰 결과를 파일로 받는다.
# 사용법: run-reviewer.sh <claude|codex> <프롬프트 파일> <결과 파일> [제한 시간(초, 기본 900)]
# 대상 저장소 루트에서 실행한다. 프롬프트는 stdin으로 넘기므로 길이 제한이 없다.
# 종료 코드: 0 성공, 1 호출 실패·시간 초과·빈 결과, 2 사용법 오류·CLI 없음
set -uo pipefail

reviewer="${1:-}" prompt_file="${2:-}" out_file="${3:-}" limit="${4:-900}"

if [ -z "$reviewer" ] || [ ! -f "$prompt_file" ] || [ -z "$out_file" ]; then
  echo "사용법: $0 <claude|codex> <프롬프트 파일> <결과 파일> [제한 시간(초)]" >&2
  exit 2
fi
command -v "$reviewer" >/dev/null 2>&1 || { echo "CLI를 찾을 수 없음: $reviewer" >&2; exit 2; }
command -v timeout >/dev/null 2>&1 || { echo "timeout 명령이 필요하다" >&2; exit 2; }

log_file="${out_file}.log"
: >"$out_file"

case "$reviewer" in
  claude)
    # 읽기·검색과 git 조회만 허용한다. dontAsk는 허용 목록 밖의 도구를 묻지 않고 거부한다.
    timeout "$limit" claude -p \
      --permission-mode dontAsk \
      --allowedTools 'Read,Grep,Glob,Bash(git diff *),Bash(git log *),Bash(git show *),Bash(git rev-parse *),Bash(git status *),Bash(git merge-base *)' \
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
