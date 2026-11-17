#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
KEEP=false
WEB=false
TEST_PATTERN=
REALTIME_B_PORT=${E2E_REALTIME_B_PORT:-18082}
[[ $REALTIME_B_PORT =~ ^[0-9]+$ ]] && ((REALTIME_B_PORT > 0 && REALTIME_B_PORT <= 65535)) || { echo 'E2E_REALTIME_B_PORT가 유효하지 않습니다.' >&2; exit 1; }
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
ports=(18000 18080 18081 18090 "$REALTIME_B_PORT")
if $WEB; then ports+=(13000); fi
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
cleanup() {
  if $KEEP; then
    printf '프로세스 PID:'
    printf ' %s' "${PIDS[@]}"
    printf '\n'
  elif ((${#PIDS[@]})); then
    kill "${PIDS[@]}" 2>/dev/null || true
    wait "${PIDS[@]}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM
if ! ./gradlew -q :core:bootJar :gateway:bootJar :realtime:bootJar :fake-pg:bootJar > "$LOG_DIR/build.log" 2>&1; then
  echo "빌드 실패: $LOG_DIR/build.log" >&2
  tail -n 30 "$LOG_DIR/build.log" >&2
  exit 1
fi
docker compose up -d --wait > "$LOG_DIR/compose.log" 2>&1 || { echo "Compose 기동 실패: $LOG_DIR/compose.log" >&2; tail -n 30 "$LOG_DIR/compose.log" >&2; exit 1; }
start() {
  local name=$1 jar=$2
  shift 2
  java -jar "$jar" "$@" > "$LOG_DIR/$name.log" 2>&1 &
  PIDS+=("$!")
}
# fake-pg는 .env의 웹훅 비밀(FAKE_PG_WEBHOOK_SECRET)이 필요하다. 다른 앱은 자체적으로 .env를 읽는다.
(set -a; source .env; set +a; exec java -jar fake-pg/build/libs/fake-pg.jar) > "$LOG_DIR/fake-pg.log" 2>&1 &
PIDS+=("$!")
start core core/build/libs/core.jar
start realtime-a realtime/build/libs/realtime.jar
start realtime-b realtime/build/libs/realtime.jar --server.port="$REALTIME_B_PORT"
start gateway gateway/build/libs/gateway.jar
health() {
  local name=$1 port=$2 pid=$3 deadline=$((SECONDS + ${E2E_HEALTH_TIMEOUT:-180}))
  local health_path=/actuator/health
  [[ $name != core && $name != gateway ]] || health_path=/actuator/health/readiness
  until curl -fsS "http://localhost:$port$health_path" 2>/dev/null | rg -q '"status"\s*:\s*"UP"'; do
    if ((SECONDS >= deadline)) || ! kill -0 "$pid" 2>/dev/null; then
      printf '%s 기동 실패. 로그: %s/%s.log\n' "$name" "$LOG_DIR" "$name" >&2
      tail -n 30 "$LOG_DIR/$name.log" >&2
      return 1
    fi
    sleep 1
  done
}
health fake-pg 18090 "${PIDS[0]}"
health core 18080 "${PIDS[1]}"
health realtime-a 18081 "${PIDS[2]}"
health realtime-b "$REALTIME_B_PORT" "${PIDS[3]}"
health gateway 18000 "${PIDS[4]}"
# 관리자 seed: ADMIN은 가입으로 얻을 수 없으므로(ADR 0034) 가입한 회원을 DB에서 승격한다. 앱 코드에는 E2E 전용 경로를 두지 않는다.
E2E_ADMIN_EMAIL="e2e-admin-$(date +%s)-$RANDOM$RANDOM@example.com"
E2E_ADMIN_PASSWORD=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
export E2E_ADMIN_EMAIL E2E_ADMIN_PASSWORD
signup_status=$(curl -s -o "$LOG_DIR/admin-seed.log" -w '%{http_code}' -X POST http://localhost:18000/api/v1/members \
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
  (cd web && pnpm build) > "$LOG_DIR/web.log" 2>&1 || { echo "web 빌드 실패: $LOG_DIR/web.log" >&2; exit 1; }
  (cd web && GATEWAY_URL=http://localhost:18000 GATEWAY_PUBLIC_URL=http://localhost:18000 REDIS_URL=redis://localhost:16379 WEB_ORIGIN=http://localhost:13000 exec pnpm start) >> "$LOG_DIR/web.log" 2>&1 &
  PIDS+=("$!")
  deadline=$((SECONDS + ${E2E_HEALTH_TIMEOUT:-180}))
  until curl -s -o /dev/null http://localhost:13000; do
    if ((SECONDS >= deadline)) || ! kill -0 "${PIDS[5]}" 2>/dev/null; then
      echo "web 기동 실패: $LOG_DIR/web.log" >&2
      exit 1
    fi
    sleep 1
  done
  args=()
  if [[ -n $TEST_PATTERN ]]; then args=(-g "$TEST_PATTERN"); fi
  if (cd web && pnpm e2e "${args[@]}") > "$LOG_DIR/web-e2e.log" 2>&1; then
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
if ./gradlew -De2e.realtimeBUrl="http://localhost:$REALTIME_B_PORT" -De2e.adminEmail="$E2E_ADMIN_EMAIL" -De2e.adminPassword="$E2E_ADMIN_PASSWORD" :e2e:e2eTest "${args[@]}" > "$LOG_DIR/test.log" 2>&1; then
  echo 'E2E 테스트 통과'
else
  shopt -s nullglob
  reports=(e2e/build/test-results/e2eTest/TEST-*.xml)
  if ((${#reports[@]})); then scripts/agent/test/print-test-failures.sh "${reports[@]}" || true; fi
  printf 'E2E 테스트 실패. 로그: %s/test.log\n' "$LOG_DIR" >&2
  exit 1
fi
