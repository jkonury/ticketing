#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DESTINATION=/home/hong/dev/tool/nginx/sites/ticketing-dev.conf
[[ -f "$ROOT/.env" ]] || { echo '.env 파일이 필요합니다.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'python3가 필요합니다.' >&2; exit 1; }
command -v envsubst >/dev/null || { echo 'envsubst가 필요합니다.' >&2; exit 1; }

TICKETING_DOMAIN=$(python3 - "$ROOT/.env" <<'PY'
import pathlib
import re
import sys

values = []
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    match = re.fullmatch(r'\s*(?:export\s+)?TICKETING_DOMAIN\s*=\s*(.*?)\s*', line)
    if match:
        value = match.group(1)
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values.append(value)
if len(values) != 1 or len(values[0]) > 253 or values[0] == 'example.invalid' or not re.fullmatch(
    r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*',
    values[0] if values else '',
):
    sys.exit('.env에 유효한 TICKETING_DOMAIN을 하나 설정하세요.')
print(values[0])
PY
)
export TICKETING_DOMAIN
mkdir -p "$ROOT/build/nginx"
tmp=$(mktemp "$ROOT/build/nginx/ticketing.XXXXXXXX")
trap 'rm -f "$tmp"' EXIT
envsubst '${TICKETING_DOMAIN}' < "$ROOT/nginx/ticketing.conf.template" > "$tmp"
install -m 644 "$tmp" "$DESTINATION"
printf 'Nginx 설정을 복사했습니다: %s\n' "$DESTINATION"
