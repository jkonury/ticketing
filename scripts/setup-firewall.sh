#!/usr/bin/env bash
# compose 브릿지(br-ticketing)에서 호스트로 오는 연결(fake-pg 웹훅, Prometheus 수집)을 UFW에 허용한다.
# 서브넷이 아닌 인터페이스 이름 기준이라 Docker 네트워크를 재생성해도 유효하다. 여러 번 실행해도 안전하다.
# 사용: sudo ./scripts/setup-firewall.sh
set -euo pipefail

IFACE=br-ticketing
PORTS=(18000 18080 18081 18090)

if ! command -v ufw >/dev/null 2>&1; then
  echo "ufw가 없어 건너뜁니다."; exit 0
fi
if [[ $EUID -ne 0 ]]; then
  echo "root 권한이 필요합니다: sudo $0" >&2; exit 1
fi
if ! ufw status | grep -q '^Status: active'; then
  echo "ufw가 비활성이라 건너뜁니다."; exit 0
fi

for port in "${PORTS[@]}"; do
  # ufw allow는 이미 같은 규칙이 있으면 "Skipping adding existing rule"로 끝나 멱등이다.
  ufw allow in on "$IFACE" to any port "$port" proto tcp comment "ticketing compose -> host"
done
ufw status numbered | grep "$IFACE" || true
