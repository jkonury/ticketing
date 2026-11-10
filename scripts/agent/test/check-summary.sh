#!/usr/bin/env bash
# Run Gradle verification tasks and output only the failure summary.
# Run Gradle verification tasks and print only the failure summary.
# Usage: scripts/agent/test/check-summary.sh [task]...   (default: check)
# Examples:
#   scripts/agent/test/check-summary.sh                  # ./gradlew check (전 모듈 컴파일·테스트·검증)
#   scripts/agent/test/check-summary.sh :core:check      # 한 모듈
#   scripts/agent/test/check-summary.sh :core:check :gateway:check
# 테스트 실행은 run-tests.sh를 쓴다(test 태스크와 --tests 필터는 여기서 막는다).
# Output on failure: failed tests (from JUnit XML), compile errors, and each
# Gradle "What went wrong" section, truncated.
# The full Gradle log stays in build/check-summary/gradle-out.txt.
# Exit code: 0 if all tasks passed, 1 otherwise, 2 on invalid arguments.

set -uo pipefail

for tool in fd rg; do
  command -v "$tool" >/dev/null || { echo "ERROR: $tool 이 필요하다 (brew install $tool)"; exit 2; }
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$ROOT" || exit 2

[ "$#" -eq 0 ] && set -- check

for arg in "$@"; do
  case "$arg" in
    test|*:test|--tests|--tests=*)
      echo "ERROR: 테스트 실행과 --tests 필터는 scripts/agent/test/run-tests.sh를 쓴다."
      exit 2
      ;;
  esac
done

OUT_DIR="build/check-summary"
LOG_FILE="$OUT_DIR/gradle-out.txt"
MARKER_FILE="$OUT_DIR/.marker"

mkdir -p "$OUT_DIR"
touch "$MARKER_FILE"
trap 'rm -f "$MARKER_FILE"' EXIT

sleep 1 # 초 단위 파일 시각에서도 이번 실행 결과를 구분한다.

./gradlew "$@" -q --continue --console=plain > "$LOG_FILE" 2>&1
if [ "$?" -eq 0 ]; then
  echo "All checks passed: $*"
  exit 0
fi

xml_files=()
while IFS= read -r xml_file; do
  if [ "$xml_file" -nt "$MARKER_FILE" ]; then
    xml_files+=("$xml_file")
  fi
done < <(fd -HI -t f '^TEST-.*\.xml$' . | rg '(^|/)build/test-results/[^/]+/TEST-[^/]*\.xml$' | sort)

if [ "${#xml_files[@]}" -gt 0 ]; then
  echo "## Failed Tests"
  echo ""
  "$SCRIPT_DIR/print-test-failures.sh" "${xml_files[@]}"
  echo ""
fi

compile_errors=$(rg -A2 '\.java:[0-9]+: error:|^e: ' "$LOG_FILE" | head -40)
if [ -n "$compile_errors" ]; then
  echo "## Compile Errors"
  echo ""
  echo "$compile_errors"
  echo ""
fi

echo "## Gradle Failures"
echo ""
if ! rg -q '^\* What went wrong:' "$LOG_FILE"; then
  tail -50 "$LOG_FILE"
else
awk '
  /^\* What went wrong:/ { in_section = 1; printed = 0; next }
  /^\* Try:/ { if (in_section) print "---"; in_section = 0; next }
  in_section && printed < 15 && $0 != "" { print; printed++ }
' "$LOG_FILE"
fi
echo ""
echo "Full log: $LOG_FILE"
exit 1
