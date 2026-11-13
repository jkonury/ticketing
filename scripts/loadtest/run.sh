#!/usr/bin/env bash
# 부하 테스트 실행기(ADR 0024). 사용은 --help 참고
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"

usage() {
  cat >&2 <<'USAGE'
사용: run.sh --scenario <smoke|queue-surge|hot-seats> [--runs N] [--failure-inject] [--jfr] [--observability] [--keep]
            [--vus N] [--k6-cpus 0-3] [--app-cpus 4-15]
SCOUTER_AGENT_DIR을 지정하면 Java 앱에 Scouter를 연결합니다.
SCOUTER_COLLECTOR_IP는 기본값 127.0.0.1입니다.
USAGE
  exit 1
}

SCENARIO=
RUNS=1
FAILURE_INJECT=false
JFR=false
OBSERVABILITY=false
KEEP=false
VUS=
K6_CPUS=0-3
APP_CPUS=4-15
need_value() { [[ $# -ge 2 ]] || { printf '%s 값이 필요합니다.\n' "$1" >&2; exit 1; }; }
while (($#)); do
  case "$1" in
    --scenario) need_value "$@"; SCENARIO=$2; shift 2 ;;
    --runs) need_value "$@"; RUNS=$2; shift 2 ;;
    --vus) need_value "$@"; VUS=$2; shift 2 ;;
    --k6-cpus) need_value "$@"; K6_CPUS=$2; shift 2 ;;
    --app-cpus) need_value "$@"; APP_CPUS=$2; shift 2 ;;
    --failure-inject) FAILURE_INJECT=true; shift ;;
    --jfr) JFR=true; shift ;;
    --observability) OBSERVABILITY=true; shift ;;
    --keep) KEEP=true; shift ;;
    -h|--help) usage ;;
    *) printf '알 수 없는 옵션: %s\n' "$1" >&2; usage ;;
  esac
done
case "$SCENARIO" in smoke|queue-surge|hot-seats) ;; *) usage ;; esac
[[ $RUNS =~ ^[1-9][0-9]*$ ]] || { echo '--runs에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
[[ -z $VUS || $VUS =~ ^[1-9][0-9]*$ ]] || { echo '--vus에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }

for command in docker curl java jq taskset; do
  command -v "$command" >/dev/null || { printf '필요한 도구가 없습니다: %s\n' "$command" >&2; exit 1; }
done
[[ -f .env ]] || { echo '.env 파일이 필요합니다.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Docker가 실행 중이어야 합니다.' >&2; exit 1; }
for port in 18000 18080 18081 18090; do
  if command -v ss >/dev/null; then
    occupied=$(ss -ltnp "sport = :$port" | tail -n +2)
  else
    occupied=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN || true)
  fi
  [[ -z $occupied ]] || { printf '포트 %s 사용 중: %s\n' "$port" "$occupied" >&2; exit 1; }
done

set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ -n "${SCOUTER_AGENT_DIR:-}" ]]; then
  [[ -f "$SCOUTER_AGENT_DIR/scouter.agent.jar" ]] || { echo "SCOUTER_AGENT_DIR에 scouter.agent.jar가 없습니다: $SCOUTER_AGENT_DIR" >&2; exit 1; }
fi

OUT="$ROOT/build/loadtest"
STAMP=$(date +%Y%m%d-%H%M%S)
SUFFIX=
if $FAILURE_INJECT; then SUFFIX=-fi; fi
RUN_ROOT="$OUT/runs/$STAMP-$SCENARIO$SUFFIX"
mkdir -p "$RUN_ROOT"

PIDS=()
SAMPLER_PID=
cleanup() {
  if [[ -n $SAMPLER_PID ]]; then
    kill "$SAMPLER_PID" 2>/dev/null || true
    wait "$SAMPLER_PID" 2>/dev/null || true
    SAMPLER_PID=
  fi
  if ((${#PIDS[@]})); then
    kill "${PIDS[@]}" 2>/dev/null || true
    wait "${PIDS[@]}" 2>/dev/null || true
    PIDS=()
  fi
}
trap cleanup EXIT INT TERM

if [[ $SCENARIO != smoke ]]; then
  "$ROOT/scripts/loadtest/build-k6.sh"
elif [[ ! -x $OUT/k6 ]]; then
  "$ROOT/scripts/loadtest/build-k6.sh"
fi

if ! ./gradlew -q :core:bootJar :gateway:bootJar :realtime:bootJar :fake-pg:bootJar > "$RUN_ROOT/build.log" 2>&1; then
  echo "빌드 실패: $RUN_ROOT/build.log" >&2
  tail -n 30 "$RUN_ROOT/build.log" >&2
  exit 1
fi

compose=(docker compose)
services=(postgres redis)
if $OBSERVABILITY; then
  compose+=(-f compose.yaml -f compose.observability.yaml)
  services+=(prometheus tempo grafana redis-exporter postgres-exporter)
fi
"${compose[@]}" up -d --wait "${services[@]}" > "$RUN_ROOT/compose.log" 2>&1 \
  || { echo "Compose 기동 실패: $RUN_ROOT/compose.log" >&2; tail -n 30 "$RUN_ROOT/compose.log" >&2; exit 1; }

# start <name> <dir> <heap> <jar> [환경변수...]  (환경변수는 NAME=VALUE 형태)
start() {
  local name=$1 dir=$2 heap=$3 jar=$4
  shift 4
  local opts=($heap) # 힙 옵션은 공백으로 나뉜 한 문자열
  opts+=("-XX:+HeapDumpOnOutOfMemoryError" "-XX:HeapDumpPath=$dir/$name.hprof")
  if $JFR; then
    opts+=("-XX:StartFlightRecording=filename=$dir/$name.jfr,dumponexit=true,settings=profile,jdk.VirtualThreadPinned#threshold=0ms")
  fi
  if [[ -n "${SCOUTER_AGENT_DIR:-}" ]]; then
    local scouter_conf=ticketing.conf
    [[ "$name" == realtime ]] && scouter_conf=ticketing-realtime.conf
    opts+=("-javaagent:$SCOUTER_AGENT_DIR/scouter.agent.jar"
      "-Dscouter.config=$ROOT/deploy/scouter/$scouter_conf"
      "-Dnet_collector_ip=${SCOUTER_COLLECTOR_IP:-127.0.0.1}"
      "-Dobj_name=$name" -Djdk.attach.allowAttachSelf=true)
    printf 'Scouter agent 연결: obj_name=%s, collector=%s:6100\n' "$name" "${SCOUTER_COLLECTOR_IP:-127.0.0.1}" >&2
  fi
  env "$@" taskset -c "$APP_CPUS" java "${opts[@]}" -jar "$jar" > "$dir/$name.log" 2>&1 &
  PIDS+=("$!")
}

health() {
  local name=$1 port=$2 pid=$3 dir=$4 deadline=$((SECONDS + 90))
  until curl -fsS "http://localhost:$port/actuator/health" 2>/dev/null | rg -q '"status"\s*:\s*"UP"'; do
    if ((SECONDS >= deadline)) || ! kill -0 "$pid" 2>/dev/null; then
      printf '%s 기동 실패. 로그: %s/%s.log\n' "$name" "$dir" "$name" >&2
      tail -n 30 "$dir/$name.log" >&2
      return 1
    fi
    sleep 1
  done
}

write_env() {
  {
    printf 'commit: %s\n' "$(git rev-parse HEAD)"
    printf 'dirty: %s\n' "$([[ -n $(git status --porcelain) ]] && echo yes || echo no)"
    printf 'jdk: %s\n' "$(java -version 2>&1 | head -n 1)"
    printf 'cpu: %s (논리 코어 %s)\n' "$(LC_ALL=C lscpu | awk -F: '/Model name/ {gsub(/^ +/, "", $2); print $2}')" "$(nproc)"
    printf 'memory: %s\n' "$(LC_ALL=C free -h | awk '/^Mem:/ {print $2}')"
    printf 'k6: %s\n' "$("$OUT/k6" version 2>&1 | head -n 1)"
    printf 'scenario: %s\nruns: %s\nfailure-inject: %s\njfr: %s\nobservability: %s\n' "$SCENARIO" "$RUNS" "$FAILURE_INJECT" "$JFR" "$OBSERVABILITY"
    printf 'k6-cpus: %s\napp-cpus: %s\n' "$K6_CPUS" "$APP_CPUS"
    printf 'os: %s\n' "$(uname -srm)"
    printf 'tracing-sampling: %s\n' "$($OBSERVABILITY && echo 0.1 || echo 비활성)"
    printf 'scouter: %s\n' "$([[ -n ${SCOUTER_AGENT_DIR:-} ]] && echo 활성 || echo 비활성)"
    printf 'heap: core 2g, realtime 1g, gateway 1g, fake-pg 512m\n'
  } > "$1/env.txt"
}

SUMMARY=()
run_once() {
  local n=$1 dir="$RUN_ROOT/$1"
  mkdir -p "$dir"
  write_env "$dir"
  local failure=0 duplicate=0 batch=() obs=()
  if $FAILURE_INJECT; then failure=0.1; duplicate=0.3; fi
  [[ $SCENARIO != hot-seats ]] || batch=(TICKETING_REALTIME_QUEUE_ADMISSION_BATCH_SIZE=1000)
  if $OBSERVABILITY; then obs=(TRACING_EXPORT_ENABLED=true TRACING_SAMPLING_PROBABILITY=0.1); fi

  start fake-pg "$dir" "-Xms512m -Xmx512m" fake-pg/build/libs/fake-pg.jar \
    FAKE_PG_WEBHOOK_URL=http://localhost:18080/api/v1/payments/webhooks/fake-pg \
    FAKE_PG_BEHAVIOR_FAILURE_RATE="$failure" FAKE_PG_BEHAVIOR_DUPLICATE_RATE="$duplicate" "${obs[@]}"
  start core "$dir" "-Xms2g -Xmx2g" core/build/libs/core.jar "${obs[@]}"
  start realtime "$dir" "-Xms1g -Xmx1g" realtime/build/libs/realtime.jar "${batch[@]}" "${obs[@]}"
  start gateway "$dir" "-Xms1g -Xmx1g" gateway/build/libs/gateway.jar "${obs[@]}"
  health fake-pg 18090 "${PIDS[0]}" "$dir" || return 1
  health core 18080 "${PIDS[1]}" "$dir" || return 1
  health realtime 18081 "${PIDS[2]}" "$dir" || return 1
  health gateway 18000 "${PIDS[3]}" "$dir" || return 1

  "$ROOT/scripts/loadtest/seed.sh" members > "$dir/seed.log" 2>&1 || { echo "회원 시드 실패: $dir/seed.log" >&2; return 1; }
  local schedule_file="$OUT/schedule-$SCENARIO.json"
  if [[ $SCENARIO != smoke ]]; then
    "$ROOT/scripts/loadtest/seed.sh" schedule --scenario "$SCENARIO" >> "$dir/seed.log" 2>&1 \
      || { echo "회차 시드 실패: $dir/seed.log" >&2; return 1; }
  fi

  local k6_args=(run --summary-export "$dir/k6-summary.json")
  if $OBSERVABILITY; then k6_args+=(-o experimental-prometheus-rw); fi
  local k6_status=0 vus_env=()
  [[ -z $VUS ]] || vus_env=(VUS="$VUS")
  local k6_from k6_to
  k6_from=$(date +%s)
  printf 'k6_start_unix_seconds: %s\n' "$k6_from" > "$dir/measurement.txt"
  env K6_PROMETHEUS_RW_SERVER_URL=http://localhost:19090/api/v1/write K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99),avg,max' \
    BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$schedule_file" \
    "${vus_env[@]}" taskset -c "$K6_CPUS" "$OUT/k6" "${k6_args[@]}" "loadtest/scenarios/$SCENARIO.ts" > "$dir/k6.log" 2>&1 &
  local k6_pid=$!
  python3 "$ROOT/scripts/loadtest/sample-resources.py" "$k6_pid" "$dir/resources.csv" > "$dir/resource-sampler.log" 2>&1 &
  SAMPLER_PID=$!
  wait "$k6_pid" || k6_status=$?
  k6_to=$(date +%s)
  printf 'k6_end_unix_seconds: %s\nk6_exit_code: %s\n' "$k6_to" "$k6_status" >> "$dir/measurement.txt"
  wait "$SAMPLER_PID" || true
  SAMPLER_PID=
  if $OBSERVABILITY; then
    sleep 6 # 마지막 scrape(5초 간격)가 Prometheus에 들어오게 한다
    "$ROOT/scripts/loadtest/prom-snapshot.sh" "$k6_from" "$k6_to" "$dir" > "$dir/prom-snapshot.log" 2>&1 \
      || echo "Prometheus 지표 추출 실패: $dir/prom-snapshot.log" >&2
  fi

  local integrity='해당 없음' integrity_ok=true
  if [[ $SCENARIO != smoke ]]; then
    sleep 10
    local schedule_id
    schedule_id=$(jq -r .scheduleId "$schedule_file")
    if "$ROOT/scripts/loadtest/verify-integrity.sh" "$schedule_id" > "$dir/integrity.log" 2>&1; then
      integrity='통과'
    else
      integrity='위반(integrity.log 참고)'
      integrity_ok=false
    fi
    cp "$OUT/integrity-$schedule_id.json" "$dir/integrity.json" 2>/dev/null || true
  fi

  cleanup # SIGTERM으로 종료해 JFR이 파일로 남게 한다

  # k6 종료 코드 99는 threshold 미달이다. 기준 측정은 미달 항목을 그대로 기록하므로 스크립트 실패로 보지 않는다.
  # smoke의 99는 checks 실패(연결 이상)라서 실패로 본다
  local thresholds='통과' script_ok=true reqs failed p95
  if ((k6_status == 99)) && [[ $SCENARIO != smoke ]]; then
    thresholds='미달(k6 종료 코드 99, 기록용이며 실패 아님)'
  elif ((k6_status != 0)); then
    thresholds="스크립트 오류(k6 종료 코드 $k6_status, k6.log 참고)"
    script_ok=false
  fi
  # 반복 중 던져진 스크립트 예외는 k6 종료 코드에 드러나지 않으므로 로그에서 찾는다
  if rg -q 'source=stacktrace|script exception' "$dir/k6.log"; then
    thresholds="$thresholds, 스크립트 예외 발생(k6.log의 source=stacktrace 참고)"
    script_ok=false
  fi
  reqs=$(jq -r '.metrics.http_reqs.count // "n/a"' "$dir/k6-summary.json" 2>/dev/null || echo n/a)
  failed=$(jq -r '.metrics.http_req_failed | (.value // .rate) // "n/a"' "$dir/k6-summary.json" 2>/dev/null || echo n/a)
  p95=$(jq -r '.metrics["http_req_duration{name:booking_create}"]["p(95)"] // "n/a"' "$dir/k6-summary.json" 2>/dev/null || echo n/a)
  SUMMARY+=("run $n: thresholds $thresholds, 요청 $reqs, http_req_failed $failed, 예매 생성 p95 $p95, 정합성 $integrity, 디렉터리 $dir")
  $script_ok && $integrity_ok
}

overall=0
for ((i = 1; i <= RUNS; i++)); do
  if ! run_once "$i"; then
    overall=1
    cleanup
    SUMMARY+=("run $i: 실패(위 메시지와 $RUN_ROOT/$i 참고)")
  fi
done

echo '=== 부하 테스트 요약 ==='
printf '%s\n' "${SUMMARY[@]}"
if $KEEP; then echo '--keep: compose 인프라는 그대로 둡니다(앱만 정리).'; fi
exit "$overall"
