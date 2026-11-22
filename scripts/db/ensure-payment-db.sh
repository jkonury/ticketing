#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

set -a
source .env
set +a
export TICKETING_PAYMENT_POSTGRES_DB="${TICKETING_PAYMENT_POSTGRES_DB:-payment_db}"
export TICKETING_PAYMENT_POSTGRES_USER="${TICKETING_PAYMENT_POSTGRES_USER:-payment}"
export TICKETING_PAYMENT_POSTGRES_PASSWORD="${TICKETING_PAYMENT_POSTGRES_PASSWORD:?결제 DB 비밀번호를 설정하세요}"

# 로컬 core 계정(POSTGRES_USER)은 슈퍼유저이므로 결제 DB의 CONNECT 제한을 우회한다.
# .env에서 비밀번호를 바꿔도 기존 결제 계정의 비밀번호는 자동으로 변경되지 않는다.
docker compose exec -T \
  -e TICKETING_PAYMENT_POSTGRES_DB -e TICKETING_PAYMENT_POSTGRES_USER -e TICKETING_PAYMENT_POSTGRES_PASSWORD \
  postgres psql -X --no-password --set=ON_ERROR_STOP=1 \
  --username "$TICKETING_POSTGRES_USER" --dbname "$TICKETING_POSTGRES_DB" \
  < deploy/postgres/init/10-payment-db.sql
