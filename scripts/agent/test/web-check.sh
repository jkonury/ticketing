#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT" || exit 1

if [ ! -d web/node_modules ]; then
  ./scripts/agent/test/run-summary.sh -C web pnpm install --frozen-lockfile || exit 1
fi

tasks=(lint typecheck test format:check)
if [ "${1:-}" = "--build" ]; then
  tasks+=(build)
elif [ "$#" -ne 0 ]; then
  echo "사용법: $0 [--build]" >&2
  exit 2
fi

failed=()
for task in "${tasks[@]}"; do
  if ! ./scripts/agent/test/run-summary.sh -C web pnpm run "$task"; then
    failed+=("$task")
  fi
done
if [ "${#failed[@]}" -gt 0 ]; then
  printf '실패한 web 검사: %s\n' "${failed[*]}" >&2
  exit 1
fi
