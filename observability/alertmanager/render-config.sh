#!/bin/sh
set -eu

# URL을 YAML에 삽입하지 않고 Alertmanager가 읽는 전용 파일에 둔다.
umask 077
config_dir=$(mktemp -d)
config_file="$config_dir/alertmanager.yml"
cat /etc/alertmanager/alertmanager.yml > "$config_file"

if [ -n "${ALERTMANAGER_WEBHOOK_URL:-}" ]; then
  printf '%s\n' "$ALERTMANAGER_WEBHOOK_URL" > "$config_dir/webhook-url"
  cat >> "$config_file" <<EOF
    webhook_configs:
      - url_file: $config_dir/webhook-url
        send_resolved: true
EOF
fi

printf '%s\n' "$config_file"
