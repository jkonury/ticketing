#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DESTINATION=/home/hong/dev/tool/nginx/sites/ticketing-dev.conf
[[ -f "$ROOT/.env" ]] || { echo '.env 파일이 필요합니다.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'python3가 필요합니다.' >&2; exit 1; }
command -v envsubst >/dev/null || { echo 'envsubst가 필요합니다.' >&2; exit 1; }

settings=$(python3 - "$ROOT/.env" <<'PY'
import pathlib
import re
import sys

values = {"TICKETING_DOMAIN": [], "TICKETING_INGRESS_PROOF": []}
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    match = re.fullmatch(r'\s*(?:export\s+)?(TICKETING_DOMAIN|TICKETING_INGRESS_PROOF)\s*=\s*(.*?)\s*', line)
    if match:
        name, value = match.groups()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[name].append(value)
domains = values["TICKETING_DOMAIN"]
if len(domains) != 1 or len(domains[0]) > 253 or domains[0] == 'example.invalid' or not re.fullmatch(
    r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*',
    domains[0] if domains else '',
):
    sys.exit('.env에 유효한 TICKETING_DOMAIN을 하나 설정하세요.')
proofs = values["TICKETING_INGRESS_PROOF"]
if len(proofs) != 1 or not re.fullmatch(r'[0-9a-f]{64}', proofs[0]):
    sys.exit('.env에 TICKETING_INGRESS_PROOF를 openssl rand -hex 32로 생성해 하나 설정하세요.')
print(domains[0], proofs[0])
PY
)
read -r TICKETING_DOMAIN TICKETING_INGRESS_PROOF <<< "$settings"
mkdir -p "$ROOT/build/nginx"
tmp=$(mktemp "$ROOT/build/nginx/ticketing.XXXXXXXX")
trap 'rm -f "$tmp"' EXIT
TICKETING_DOMAIN="$TICKETING_DOMAIN" TICKETING_INGRESS_PROOF="$TICKETING_INGRESS_PROOF" \
    envsubst '${TICKETING_DOMAIN} ${TICKETING_INGRESS_PROOF}' < "$ROOT/nginx/ticketing.conf.template" > "$tmp"
install -m 600 "$tmp" "$DESTINATION"
printf 'Nginx 설정을 복사했습니다: %s\n' "$DESTINATION"
