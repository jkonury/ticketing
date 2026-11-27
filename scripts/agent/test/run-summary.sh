#!/usr/bin/env bash
# 명령의 전체 로그를 보관하고 성공 시 한 줄, 실패 시 마지막 일부만 출력한다.
# 사용법: scripts/agent/test/run-summary.sh [-C 디렉터리] [--] 명령 [인자...]
# 예: scripts/agent/test/run-summary.sh -- ./gradlew :core:compileKotlin
#     scripts/agent/test/run-summary.sh -C web pnpm run build
# pipeline이 필요하면: scripts/agent/test/run-summary.sh bash -o pipefail -c '...'
# 기본은 현재 디렉터리, -C는 호출 위치 기준 상대 경로나 절대 경로이다.
# 명령 인자와 종료 코드를 그대로 유지한다.

set -uo pipefail

usage() {
  echo "사용법: $0 [-C 디렉터리] [--] 명령 [인자...]" >&2
}

WORK_DIR=.
if [ "${1:-}" = "-C" ]; then
  if [ "$#" -lt 2 ] || [ -z "$2" ]; then
    usage
    exit 2
  fi
  WORK_DIR=$2
  shift 2
fi
if [ "${1:-}" = "--" ]; then
  shift
fi
if [ "$#" -eq 0 ]; then
  usage
  exit 2
fi

# 잘못된 실행 위치에서는 명령과 로그 생성을 시작하지 않는다.
if ! (cd -- "$WORK_DIR") 2>/dev/null; then
  printf '실행 디렉터리에 접근할 수 없습니다: %s\n' "$WORK_DIR" >&2
  exit 2
fi

# 실행마다 다른 파일을 쓰고 다른 사용자가 읽을 수 없게 만든다.
ROOT="$(cd -- "$(dirname -- "$0")/../../.." && pwd -P)" || exit 2
LOG_DIR="$ROOT/build/agent/logs"
mkdir -p -- "$LOG_DIR" || exit 2
# macOS mktemp는 템플릿이 X로 끝나야 치환하므로 확장자를 뒤에 붙이지 않는다.
LOG_FILE=$(mktemp "$LOG_DIR/ticketing-command.XXXXXXXX") || exit 2

(cd -- "$WORK_DIR" && "$@") > "$LOG_FILE" 2>&1
COMMAND_EXIT=$?

if [ "$COMMAND_EXIT" -eq 0 ]; then
  printf '성공 (종료 코드 0). 전체 로그: %s\n' "$LOG_FILE"
else
  printf '실패 (종료 코드 %s). 마지막 최대 80줄·12000바이트:\n' "$COMMAND_EXIT"
  # 줄 수와 바이트 수를 모두 제한해 긴 한 줄이나 trace도 출력이 폭증하지 않게 한다.
  tail -c 12000 "$LOG_FILE" | tail -n 80
  printf '\n전체 로그: %s\n' "$LOG_FILE"
fi

exit "$COMMAND_EXIT"
