#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
KEEP=false
WEB=false
TEST_PATTERN=
APP_PORT_OFFSET=${E2E_APP_PORT_OFFSET:-0}
[[ $APP_PORT_OFFSET =~ ^(0|[1-9][0-9]{0,4})$ ]] && ((APP_PORT_OFFSET <= 47445)) || { echo 'E2E_APP_PORT_OFFSET이 유효하지 않습니다.' >&2; exit 1; }
GATEWAY_PORT=$((18000 + APP_PORT_OFFSET))
CORE_PORT=$((18080 + APP_PORT_OFFSET))
REALTIME_PORT=$((18081 + APP_PORT_OFFSET))
REALTIME_B_PORT=${E2E_REALTIME_B_PORT:-$((18082 + APP_PORT_OFFSET))}
PAYMENT_PORT=$((18085 + APP_PORT_OFFSET))
SETTLEMENT_PORT=$((18086 + APP_PORT_OFFSET))
FAKE_PG_PORT=$((18090 + APP_PORT_OFFSET))
WEB_PORT=$((13000 + APP_PORT_OFFSET))
GATEWAY_URL="http://localhost:$GATEWAY_PORT"
WEB_URL="http://localhost:$WEB_PORT"
PAYMENT_URL="http://localhost:$PAYMENT_PORT"
export FAKE_PG_BASE_URL="http://localhost:$FAKE_PG_PORT"
[[ $REALTIME_B_PORT =~ ^[1-9][0-9]{0,4}$ ]] && ((REALTIME_B_PORT <= 65535)) || { echo 'E2E_REALTIME_B_PORT가 유효하지 않습니다.' >&2; exit 1; }
while (($#)); do
  case "$1" in
    --keep) KEEP=true; shift ;;
    --web) WEB=true; shift ;;
    --tests) [[ $# -ge 2 ]] || { echo '--tests 패턴이 필요합니다.' >&2; exit 1; }; TEST_PATTERN=$2; shift 2 ;;
    *) printf '알 수 없는 옵션: %s\n' "$1" >&2; exit 1 ;;
  esac
done
for command in docker curl java; do
  command -v "$command" >/dev/null || { printf '필요한 도구가 없습니다: %s\n' "$command" >&2; exit 1; }
done
[[ -f .env ]] || { echo '.env 파일이 필요합니다.' >&2; exit 1; }
# 전용 DB와 E2E 웹훅 재전송에 필요한 키. 값은 출력하지 않는다
missing=$(set -a; source .env; set +a
  for key in TICKETING_PAYMENT_POSTGRES_{DB,USER,PASSWORD} TICKETING_SETTLEMENT_POSTGRES_{DB,USER,PASSWORD} TICKETING_JWT_SECRET FAKE_PG_WEBHOOK_SECRET; do
    [[ -n ${!key:-} ]] || printf '%s ' "$key"
  done)
[[ -z $missing ]] || { printf '.env에 E2E 설정이 없습니다: %s(.env.example 참고)\n' "$missing" >&2; exit 1; }
ports=("$GATEWAY_PORT" "$CORE_PORT" "$REALTIME_PORT" "$REALTIME_B_PORT" "$PAYMENT_PORT" "$SETTLEMENT_PORT" "$FAKE_PG_PORT")
if $WEB; then ports+=("$WEB_PORT"); fi
if command -v ss >/dev/null; then
  for port in "${ports[@]}"; do
    occupied=$(ss -ltnp "sport = :$port" | tail -n +2)
    [[ -z "$occupied" ]] || { printf '포트 %s 사용 중: %s\n' "$port" "$occupied" >&2; exit 1; }
  done
elif command -v lsof >/dev/null; then
  for port in "${ports[@]}"; do
    occupied=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN || true)
    [[ -z "$occupied" ]] || { printf '포트 %s 사용 중: %s\n' "$port" "$occupied" >&2; exit 1; }
  done
else
  echo '포트 확인에 ss 또는 lsof가 필요합니다.' >&2; exit 1
fi

mkdir -p build/e2e/logs
LOG_DIR="$ROOT/build/e2e/logs"
PIDS=()
WEB_PGID=
cleanup() {
  if $KEEP; then
    printf '프로세스 PID:'
    printf ' %s' "${PIDS[@]}"
    printf '\n'
  elif ((${#PIDS[@]})); then
    # pnpm start는 next-server를 자식으로 띄우므로 web은 프로세스 그룹째 종료한다
    [[ -z $WEB_PGID ]] || kill -- "-$WEB_PGID" 2>/dev/null || true
    kill "${PIDS[@]}" 2>/dev/null || true
    wait "${PIDS[@]}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM
if ! ./gradlew -q :core:bootJar :gateway:bootJar :realtime:bootJar :fake-pg:bootJar :payment:bootJar :settlement:bootJar \
  :core-batch:bootJar :payment-batch:bootJar :settlement-batch:bootJar > "$LOG_DIR/build.log" 2>&1; then
  echo "빌드 실패: $LOG_DIR/build.log" >&2
  tail -n 30 "$LOG_DIR/build.log" >&2
  exit 1
fi
docker compose up -d --wait postgres redis kafka > "$LOG_DIR/compose.log" 2>&1 || { echo "Compose 기동 실패: $LOG_DIR/compose.log" >&2; tail -n 30 "$LOG_DIR/compose.log" >&2; exit 1; }
if $WEB; then
  export TICKETING_MINIO_CORS_ORIGINS="${TICKETING_MINIO_CORS_ORIGINS:-$WEB_URL,http://127.0.0.1:$WEB_PORT}"
  if ! docker compose up -d --wait minio >> "$LOG_DIR/compose.log" 2>&1 ||
     ! docker compose run --rm --no-deps minio-init >> "$LOG_DIR/compose.log" 2>&1; then
    echo "포스터 저장소 기동 실패: $LOG_DIR/compose.log" >&2
    tail -n 30 "$LOG_DIR/compose.log" >&2
    exit 1
  fi
fi
# 기존 볼륨에도 전용 DB를 준비한다
./scripts/db/ensure-payment-db.sh > "$LOG_DIR/ensure-payment-db.log" 2>&1 || { echo "payment_db 준비 실패: $LOG_DIR/ensure-payment-db.log" >&2; tail -n 30 "$LOG_DIR/ensure-payment-db.log" >&2; exit 1; }
./scripts/db/ensure-settlement-db.sh > "$LOG_DIR/ensure-settlement-db.log" 2>&1 || { echo "settlement_db 준비 실패: $LOG_DIR/ensure-settlement-db.log" >&2; tail -n 30 "$LOG_DIR/ensure-settlement-db.log" >&2; exit 1; }
start() {
  local name=$1 jar=$2
  shift 2
  java -jar "$jar" "$@" > "$LOG_DIR/$name.log" 2>&1 &
  PIDS+=("$!")
}
# fake-pg는 .env의 웹훅 비밀(FAKE_PG_WEBHOOK_SECRET)이 필요하다. 다른 앱은 자체적으로 .env를 읽는다.
# 웹훅은 payment 앱을 직접 호출한다. 승인 보류 제어는 E2E에서만 켜고, 실패·타임아웃·중복 확률을 0으로 고정한다
(set -a; source .env; set +a; exec java -jar fake-pg/build/libs/fake-pg.jar --fake-pg.test-controls.enabled=true \
  --server.port="$FAKE_PG_PORT" --fake-pg.webhook.url="$PAYMENT_URL/api/v1/payments/webhooks/fake-pg" \
  --fake-pg.behavior.failure-rate=0 --fake-pg.behavior.timeout-rate=0 --fake-pg.behavior.duplicate-rate=0) > "$LOG_DIR/fake-pg.log" 2>&1 &
PIDS+=("$!")
if $WEB; then
  poster_bucket=$(source .env; printf '%s' "${TICKETING_POSTER_BUCKET:-ticketing-posters}")
  poster_endpoint="http://127.0.0.1:${TICKETING_MINIO_PORT:-19010}"
  TICKETING_POSTER_ENDPOINT="$poster_endpoint" TICKETING_POSTER_UPLOAD_ENDPOINT="$poster_endpoint" \
    TICKETING_POSTER_PUBLIC_BASE_URL="$poster_endpoint/$poster_bucket" start core core/build/libs/core.jar --server.port="$CORE_PORT"
else
  start core core/build/libs/core.jar --server.port="$CORE_PORT"
fi
start payment payment/build/libs/payment.jar --server.port="$PAYMENT_PORT"
start settlement settlement/build/libs/settlement.jar --server.port="$SETTLEMENT_PORT"
start realtime-a realtime/build/libs/realtime.jar --server.port="$REALTIME_PORT"
start realtime-b realtime/build/libs/realtime.jar --server.port="$REALTIME_B_PORT"
TICKETING_INGRESS_PROOF= start gateway gateway/build/libs/gateway.jar --server.port="$GATEWAY_PORT" \
  --ticketing.gateway.core-uri="http://localhost:$CORE_PORT" --ticketing.gateway.payment-uri="$PAYMENT_URL" \
  --ticketing.gateway.settlement-uri="http://localhost:$SETTLEMENT_PORT" --ticketing.gateway.realtime-uri="http://localhost:$REALTIME_PORT" \
  --ticketing.gateway.cors.allowed-origins="$WEB_URL"
health() {
  local name=$1 port=$2 pid=$3 deadline=$((SECONDS + ${E2E_HEALTH_TIMEOUT:-180}))
  local health_path=/actuator/health
  [[ $name != core && $name != payment && $name != settlement && $name != gateway ]] || health_path=/actuator/health/readiness
  until curl -fsS "http://localhost:$port$health_path" 2>/dev/null | rg -q '"status"\s*:\s*"UP"'; do
    if ((SECONDS >= deadline)) || ! kill -0 "$pid" 2>/dev/null; then
      printf '%s 기동 실패. 로그: %s/%s.log\n' "$name" "$LOG_DIR" "$name" >&2
      tail -n 30 "$LOG_DIR/$name.log" >&2
      return 1
    fi
    sleep 1
  done
}
health fake-pg "$FAKE_PG_PORT" "${PIDS[0]}"
health core "$CORE_PORT" "${PIDS[1]}"
health payment "$PAYMENT_PORT" "${PIDS[2]}"
health settlement "$SETTLEMENT_PORT" "${PIDS[3]}"
health realtime-a "$REALTIME_PORT" "${PIDS[4]}"
health realtime-b "$REALTIME_B_PORT" "${PIDS[5]}"
health gateway "$GATEWAY_PORT" "${PIDS[6]}"
# 관리자 seed: ADMIN은 가입으로 얻을 수 없으므로(ADR 0034) 가입한 회원을 DB에서 승격한다. 앱 코드에는 E2E 전용 경로를 두지 않는다.
E2E_ADMIN_EMAIL="e2e-admin-$(date +%s)-$RANDOM$RANDOM@example.com"
E2E_ADMIN_PASSWORD=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
export E2E_ADMIN_EMAIL E2E_ADMIN_PASSWORD
signup_status=$(curl -s -o "$LOG_DIR/admin-seed.log" -w '%{http_code}' -X POST "$GATEWAY_URL/api/v1/members" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$E2E_ADMIN_EMAIL\",\"password\":\"$E2E_ADMIN_PASSWORD\",\"name\":\"E2E Admin\",\"role\":\"USER\"}") || signup_status=000
[[ $signup_status == 201 ]] || { printf '관리자 seed 가입 실패(HTTP %s). 로그: %s/admin-seed.log\n' "$signup_status" "$LOG_DIR" >&2; exit 1; }
promoted=$(set -a; source .env; set +a
  docker compose exec -T postgres psql -U "$TICKETING_POSTGRES_USER" -d "$TICKETING_POSTGRES_DB" -v ON_ERROR_STOP=1 -qtA \
    -c "UPDATE member.member SET role='ADMIN' WHERE email='$E2E_ADMIN_EMAIL' RETURNING 1" 2>> "$LOG_DIR/admin-seed.log") || promoted=
[[ $promoted == 1 ]] || { printf '관리자 seed 승격 실패. 로그: %s/admin-seed.log\n' "$LOG_DIR" >&2; exit 1; }
if $WEB; then
  if [[ ! -d web/node_modules ]]; then
    (cd web && pnpm install --frozen-lockfile) > "$LOG_DIR/web.log" 2>&1 || { echo "web 설치 실패: $LOG_DIR/web.log" >&2; exit 1; }
  fi
  (cd web && TICKETING_INGRESS_PROOF= pnpm build) > "$LOG_DIR/web.log" 2>&1 || { echo "web 빌드 실패: $LOG_DIR/web.log" >&2; exit 1; }
  (cd web && TICKETING_INGRESS_PROOF= GATEWAY_URL="$GATEWAY_URL" GATEWAY_PUBLIC_URL="$GATEWAY_URL" \
    REDIS_URL="redis://localhost:${TICKETING_REDIS_PORT:-16379}" WEB_ORIGIN="$WEB_URL" exec setsid pnpm exec next start -p "$WEB_PORT") >> "$LOG_DIR/web.log" 2>&1 &
  PIDS+=("$!")
  WEB_PGID=$!
  deadline=$((SECONDS + ${E2E_HEALTH_TIMEOUT:-180}))
  until curl -s -o /dev/null "$WEB_URL"; do
    if ((SECONDS >= deadline)) || ! kill -0 "$WEB_PGID" 2>/dev/null; then
      echo "web 기동 실패: $LOG_DIR/web.log" >&2
      exit 1
    fi
    sleep 1
  done
  args=()
  if [[ -n $TEST_PATTERN ]]; then args=(-g "$TEST_PATTERN"); fi
  if (cd web && E2E_WEB_URL="$WEB_URL" E2E_GATEWAY_URL="$GATEWAY_URL" pnpm e2e "${args[@]}") > "$LOG_DIR/web-e2e.log" 2>&1; then
    echo '웹 E2E 테스트 통과'
  else
    echo "웹 E2E 테스트 실패: $LOG_DIR/web-e2e.log" >&2
    tail -n 40 "$LOG_DIR/web-e2e.log" >&2
    exit 1
  fi
  exit 0
fi
args=()
if [[ -n $TEST_PATTERN ]]; then args=(--tests "$TEST_PATTERN"); fi
# Batch E2E는 각 앱의 배치를 별도 프로세스로 실행하고 해당 DB에서 검증한다
db_user=$(set -a; source .env; set +a; printf '%s' "$TICKETING_POSTGRES_USER")
db_name=$(set -a; source .env; set +a; printf '%s' "$TICKETING_POSTGRES_DB")
payment_db_user=$(set -a; source .env; set +a; printf '%s' "$TICKETING_PAYMENT_POSTGRES_USER")
payment_db_name=$(set -a; source .env; set +a; printf '%s' "$TICKETING_PAYMENT_POSTGRES_DB")
settlement_db_user=$(set -a; source .env; set +a; printf '%s' "$TICKETING_SETTLEMENT_POSTGRES_USER")
settlement_db_name=$(set -a; source .env; set +a; printf '%s' "$TICKETING_SETTLEMENT_POSTGRES_DB")
# 웹훅 재전송 E2E가 payment 앱에 서명된 요청을 보내기 위한 비밀. 명령행에 노출하지 않도록 환경 변수로 넘긴다
E2E_WEBHOOK_SECRET=$(set -a; source .env; set +a; printf '%s' "$FAKE_PG_WEBHOOK_SECRET")
export E2E_WEBHOOK_SECRET
if ./gradlew -De2e.projectDir="$ROOT" -De2e.coreBatchJar="$ROOT/core-batch/build/libs/core-batch.jar" \
  -De2e.paymentBatchJar="$ROOT/payment-batch/build/libs/payment-batch.jar" -De2e.settlementBatchJar="$ROOT/settlement-batch/build/libs/settlement-batch.jar" \
  -De2e.dbUser="$db_user" -De2e.dbName="$db_name" -De2e.paymentDbUser="$payment_db_user" -De2e.paymentDbName="$payment_db_name" \
  -De2e.settlementDbUser="$settlement_db_user" -De2e.settlementDbName="$settlement_db_name" -De2e.paymentUrl="$PAYMENT_URL" \
  -De2e.gatewayUrl="$GATEWAY_URL" -De2e.fakePgUrl="$FAKE_PG_BASE_URL" -De2e.realtimeBUrl="http://localhost:$REALTIME_B_PORT" \
  -De2e.adminEmail="$E2E_ADMIN_EMAIL" -De2e.adminPassword="$E2E_ADMIN_PASSWORD" :e2e:e2eTest "${args[@]}" > "$LOG_DIR/test.log" 2>&1; then
  echo 'E2E 테스트 통과'
else
  shopt -s nullglob
  reports=(e2e/build/test-results/e2eTest/TEST-*.xml)
  if ((${#reports[@]})); then scripts/agent/test/print-test-failures.sh "${reports[@]}" || true; fi
  printf 'E2E 테스트 실패. 로그: %s/test.log\n' "$LOG_DIR" >&2
  exit 1
fi
