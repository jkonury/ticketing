#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
prometheus_image=prom/prometheus:v3.5.0
alertmanager_image=prom/alertmanager:v0.32.0

promtool() {
  docker run --rm --entrypoint promtool \
    -v "$root/observability/prometheus:/cfg:ro" "$prometheus_image" "$@"
}

promtool check config /cfg/prometheus.yml
rule_files=(observability/prometheus/rules/*.rules.json)
promtool check rules "${rule_files[@]/observability\/prometheus/\/cfg}"
test_files=(observability/prometheus/rules/tests/*.test.yml)
promtool test rules "${test_files[@]/observability\/prometheus/\/cfg}"

check_alertmanager() {
  docker run --rm --entrypoint /bin/sh \
    -e "ALERTMANAGER_WEBHOOK_URL=$1" \
    -v "$root/observability/alertmanager:/etc/alertmanager:ro" "$alertmanager_image" -ec '
      config_file=$(sh /etc/alertmanager/render-config.sh)
      /bin/amtool check-config "$config_file"
      if [ -n "$ALERTMANAGER_WEBHOOK_URL" ]; then
        grep -q webhook_configs "$config_file"
        test "$(cat "${config_file%/*}/webhook-url")" = "$ALERTMANAGER_WEBHOOK_URL"
      else
        ! grep -q webhook_configs "$config_file"
      fi
    '
}

check_alertmanager ''
check_alertmanager 'https://example.invalid/ticketing-alerts'

docker run --rm \
  -v "$root/scripts/observability/check.sh:/mnt/check.sh:ro" \
  -v "$root/observability/alertmanager/render-config.sh:/mnt/render-config.sh:ro" \
  koalaman/shellcheck:v0.11.0 /mnt/check.sh /mnt/render-config.sh

python3 scripts/observability/check.py
