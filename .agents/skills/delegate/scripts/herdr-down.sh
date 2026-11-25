#!/usr/bin/env bash
# herdr-up.sh가 만든 pane만 닫는다. 기존에 있던 에이전트 pane은 건드리지 않는다.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require_herdr
[ -f "$STATE_FILE" ] || die "상태 파일이 없다: $STATE_FILE"

while IFS= read -r pane; do
  [ -n "$pane" ] || continue
  herdr pane close "$pane" >/dev/null 2>&1 && echo "closed: $pane" || echo "skip: $pane (이미 닫힘)"
done < <(jq -r '.created[]?' "$STATE_FILE")
rm -f "$STATE_FILE"
