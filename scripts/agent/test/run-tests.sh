#!/usr/bin/env bash
# Run Gradle test tasks and print only the failed test cases.
# Usage: scripts/agent/test/run-tests.sh [:모듈:test] [--tests "패턴"]...
# Examples:
#   scripts/agent/test/run-tests.sh                                        # 모든 모듈 test
#   scripts/agent/test/run-tests.sh :core:test --tests "*BookingConcurrencyTest"
#   scripts/agent/test/run-tests.sh :core:test --tests "*A" --tests "*B"
# --tests는 모듈 경로와 함께 쓴다. 모든 모듈에 적용하면 일치하는 테스트가 없는 모듈이 실패한다.
# 종료 코드: 0 전부 통과, 1 실패 있음, 2 실행 오류(결과 파일 없음)

set -uo pipefail

for tool in fd rg; do
  command -v "$tool" >/dev/null || { echo "ERROR: $tool 이 필요하다 (brew install $tool)"; exit 2; }
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$ROOT" || exit 2

# 인자가 없거나 옵션부터 시작하면 test 태스크를 앞에 붙인다.
if [ "$#" -eq 0 ] || [[ "$1" == -* ]]; then
  set -- test "$@"
fi

MARKER_DIR="build/run-tests"
MARKER_FILE="$MARKER_DIR/.marker"
LOG_FILE="$MARKER_DIR/gradle-test-out.txt"

mkdir -p "$MARKER_DIR"
touch "$MARKER_FILE"
trap 'rm -f "$MARKER_FILE"' EXIT

sleep 1 # 초 단위 파일 시각에서도 이번 실행 결과를 구분한다.

./gradlew "$@" --continue --console=plain > "$LOG_FILE" 2>&1
TEST_EXIT=$?

if [ "$TEST_EXIT" -eq 0 ]; then
  echo "All tests passed: $*"
  python3 - "$LOG_FILE" <<'RESULTS'
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    match = re.match(r"> Task :([^: ]+):([^: ]*[Tt]est)\b(.*)", line)
    if not match:
        continue
    module, task, status = match.groups()
    if "NO-SOURCE" in status or "SKIPPED" in status:
        print(f"  :{module}:{task}: {status.strip()}")
        continue
    reports = pathlib.Path(module, "build", "test-results", task).glob("TEST-*.xml")
    total = sum(int(ET.parse(report).getroot().get("tests", "0")) for report in reports)
    print(f"  :{module}:{task}: {total}개 {status.strip()}")
RESULTS
  exit 0
fi

# 이번 실행에서 새로 만들어진 결과 파일만 대상으로 한다. 모듈마다 build/test-results/test가 있다.
xml_files=()
while IFS= read -r xml_file; do
  if [ "$xml_file" -nt "$MARKER_FILE" ]; then
    xml_files+=("$xml_file")
  fi
done < <(fd -HI -t f '^TEST-.*\.xml$' . | rg '(^|/)build/test-results/[^/]+/TEST-[^/]*\.xml$' | sort)

if [ "${#xml_files[@]}" -eq 0 ]; then
  echo "ERROR: 이번 실행에서 새 TEST-*.xml이 생기지 않았다. 컴파일 실패나 설정 오류일 수 있다."
  echo "Gradle 마지막 로그 ($LOG_FILE):"
  tail -50 "$LOG_FILE"
  exit 2
fi

echo "## Failed Tests"
echo ""
for xml_file in "${xml_files[@]}"; do
  if ! rg -q 'failures="0"' "$xml_file" || ! rg -q 'errors="0"' "$xml_file"; then
    echo "결과: $xml_file"
    "$SCRIPT_DIR/print-test-failures.sh" "$xml_file"
  fi
done
echo "## Gradle Errors"
rg -n -m 20 '^e: |^Execution failed|^> Task .*FAILED|^> A failure occurred' "$LOG_FILE" || true
echo "전체 로그: $LOG_FILE"
exit 1
