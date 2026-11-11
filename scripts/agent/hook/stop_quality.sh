#!/usr/bin/env bash
# Stop hook (Claude Code, Codex 공용). Kotlin 변경이 남아 있으면 ktlintCheck detekt를 돌리고,
# 실패하면 exit 2 + stderr로 에이전트가 이어서 고치게 한다.
# 같은 턴에서 한 번 이어 갔다면(stop_hook_active) 무한 루프를 막기 위해 통과시킨다.
set -uo pipefail

INPUT="$(cat)"
if printf '%s' "$INPUT" | rg -q '"stop_hook_active"\s*:\s*true'; then
  exit 0
fi

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cd "$ROOT" || exit 0

# 커밋되지 않은 Kotlin 변경이 없으면 검사하지 않는다.
if ! git status --porcelain | rg -q '\.(kt|kts)$'; then
  exit 0
fi

if OUT="$(./scripts/agent/test/check-summary.sh ktlintCheck detekt 2>&1)"; then
  exit 0
fi

{
  echo "ktlintCheck/detekt 실패. ./gradlew ktlintFormat 후 남은 지적을 코드로 고친다. @Suppress·baseline 추가로 우회하지 않는다."
  printf '%s\n' "$OUT" | head -60
} >&2
exit 2
