#!/usr/bin/env bash
# 부하 테스트 실행기(ADR 0024). 사용은 --help 참고
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
source "$ROOT/scripts/lib/scouter.sh"

usage() {
  cat >&2 <<'USAGE'
사용: run.sh --scenario <smoke|queue-surge|hot-seats|seat-conflict-refund> [--runs N] [--failure-inject] [--jfr] [--observability] [--keep]
            [--vus N] [--k6-cpus 0-3] [--app-cpus 4-15] [--no-scouter]
            [--warmup [--warmup-rounds N]] [--no-rate-limit] [--aot-cache [--aot-train-rounds N]]
            [--tool k6|gatling] [--gatling-heap 2g] [--gatling-report]
--warmup은 같은 앱(JVM 재시작 없음)에서 별도 회차로 같은 시나리오 부하를 N회 보낸 뒤, 비동기 처리(결제 웹훅·확정, 대기열 정리)가
끝났는지 확인하고 새 회차·좌석으로 측정합니다. 기본은 워밍업 없는 콜드 실행입니다.
--warmup-rounds는 워밍업 횟수입니다(기본 5, 고정). 회차별 결과는 warmup-k6-summary-<n>.json, warmup-settle-<n>.json에 남깁니다.
서버 지표는 본 부하 직전·직후 직접 수집한 누적 카운터의 차분(server-delta.json)으로 계산합니다.
--aot-cache는 core·gateway·realtime을 jar로 풀어 학습 기동(부하 N회, 기본 15)으로 AOT 캐시를 만든 뒤, 측정 기동마다 그 캐시로 띄웁니다(워밍업 요청 없음).
--no-rate-limit은 Gateway rate limiter를 끄는 대조 실험용입니다.
SCOUTER_AGENT_DIR을 지정하면 Java 앱에 Scouter를 연결합니다.
--no-scouter는 .env의 Scouter 설정도 끄고 대조 측정합니다.
seat-conflict-refund는 선점 키 유실을 주입한 보상 정합성 검증입니다(100쌍·회원 200명, 결제 실패율 0·웹훅 중복률 30%). 정상 A·B 성능 비교와 별도로 해석하며
--warmup·--failure-inject와 함께 쓰지 않습니다.
--tool gatling은 k6 대신 Gatling(loadtest/gatling)으로 같은 시나리오를 실행합니다. 부하 도구 메모리 비교용이라 queue-surge·hot-seats만 지원하며
--warmup·--aot-cache와 함께 쓰지 않습니다. Gradle 없이 java를 직접 띄워 CPU 고정(--k6-cpus)·RSS 샘플링이 k6와 같은 조건이 되고,
JVM 힙은 --gatling-heap(기본 2g)으로 고정합니다. 기본은 리포트를 생략하며(-nr), --gatling-report로 HTML 리포트를 생성합니다.
리포트는 회차별 gatling-results/<실행 디렉터리>/index.html에 저장됩니다. 리포트 생성 시간·메모리도 측정에 포함됩니다.
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
NO_SCOUTER=false
WARMUP=false
WARMUP_ROUNDS=5
# 워밍업 한 회차의 비동기 처리 완료 대기 상한(초)과 대기열 정리 대기 상한(초). 넘으면 그 실행은 실패로 둔다
WARMUP_SETTLE_TIMEOUT=120
WARMUP_QUEUE_TIMEOUT=30
METRIC_TARGETS=(core=18080 realtime=18081 gateway=18000 fake-pg=18090)
AOT_CACHE=false
AOT_TRAIN_ROUNDS=15
NO_RATE_LIMIT=false
TOOL=k6
GATLING_HEAP=2g
GATLING_REPORT=false
GATLING_DIST="$ROOT/loadtest/gatling/build/gatling-dist"
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
    --no-scouter) NO_SCOUTER=true; shift ;;
    --warmup) WARMUP=true; shift ;;
    --warmup-rounds) need_value "$@"; WARMUP_ROUNDS=$2; shift 2 ;;
    --no-rate-limit) NO_RATE_LIMIT=true; shift ;;
    --tool) need_value "$@"; TOOL=$2; shift 2 ;;
    --gatling-heap) need_value "$@"; GATLING_HEAP=$2; shift 2 ;;
    --gatling-report) GATLING_REPORT=true; shift ;;
    --aot-cache) AOT_CACHE=true; shift ;;
    --aot-train-rounds) need_value "$@"; AOT_TRAIN_ROUNDS=$2; shift 2 ;;
    -h|--help) usage ;;
    *) printf '알 수 없는 옵션: %s\n' "$1" >&2; usage ;;
  esac
done
case "$SCENARIO" in smoke|queue-surge|hot-seats|seat-conflict-refund) ;; *) usage ;; esac
if [[ $SCENARIO == seat-conflict-refund ]] && { $WARMUP || $FAILURE_INJECT || $AOT_CACHE; }; then
  echo 'seat-conflict-refund는 --warmup, --failure-inject, --aot-cache와 함께 쓰지 않습니다.' >&2; exit 1
fi
case "$TOOL" in k6|gatling) ;; *) echo '--tool에는 k6 또는 gatling이 필요합니다.' >&2; exit 1 ;; esac
if $GATLING_REPORT && [[ $TOOL != gatling ]]; then
  echo '--gatling-report는 --tool gatling과 함께 사용해야 합니다.' >&2; exit 1
fi
[[ $GATLING_HEAP =~ ^[1-9][0-9]*[mMgG]$ ]] || { echo '--gatling-heap에는 512m, 4g 같은 값이 필요합니다.' >&2; exit 1; }
if [[ $TOOL == gatling ]]; then
  [[ $SCENARIO == queue-surge || $SCENARIO == hot-seats ]] || { echo '--tool gatling은 queue-surge, hot-seats만 지원합니다.' >&2; exit 1; }
  { $WARMUP || $AOT_CACHE; } && { echo '--tool gatling은 --warmup, --aot-cache와 함께 쓰지 않습니다.' >&2; exit 1; }
fi
[[ $RUNS =~ ^[1-9][0-9]*$ ]] || { echo '--runs에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
[[ $WARMUP_ROUNDS =~ ^[1-9][0-9]*$ ]] || { echo '--warmup-rounds에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
[[ $AOT_TRAIN_ROUNDS =~ ^[1-9][0-9]*$ ]] || { echo '--aot-train-rounds에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
$AOT_CACHE && $WARMUP && { echo '--aot-cache와 --warmup은 함께 쓰지 않습니다(콜드 + AOT 조건).' >&2; exit 1; }
[[ -z $VUS || $VUS =~ ^[1-9][0-9]*$ ]] || { echo '--vus에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
[[ $SCENARIO != seat-conflict-refund || -z $VUS ]] || { echo 'seat-conflict-refund는 규모(100쌍)를 고정하므로 --vus를 쓰지 않습니다.' >&2; exit 1; }

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

# 실행 시 지정한 SCOUTER_AGENT_DIR(예: 패치 에이전트 대조 측정)이 .env 값보다 우선한다.
preset_scouter_agent_dir=${SCOUTER_AGENT_DIR:-}
set -a
# shellcheck disable=SC1091
source .env
set +a
[[ -z $preset_scouter_agent_dir ]] || SCOUTER_AGENT_DIR=$preset_scouter_agent_dir
if $NO_SCOUTER; then unset SCOUTER_AGENT_DIR; fi

if [[ -n "${SCOUTER_AGENT_DIR:-}" ]]; then
  [[ -f "$SCOUTER_AGENT_DIR/scouter.agent.jar" ]] || { echo "SCOUTER_AGENT_DIR에 scouter.agent.jar가 없습니다: $SCOUTER_AGENT_DIR" >&2; exit 1; }
fi

OUT="$ROOT/build/loadtest"
STAMP=$(date +%Y%m%d-%H%M%S)
SUFFIX=
if $FAILURE_INJECT; then SUFFIX=-fi; fi
RUN_ROOT="$OUT/runs/$STAMP-$SCENARIO$SUFFIX"
mkdir -p "$RUN_ROOT"
git diff --binary HEAD > "$RUN_ROOT/source.patch"
# 미커밋 신규 소스도 측정 당시 코드를 재구성할 수 있게 남긴다.
while IFS= read -r -d '' source_file; do
  diff_status=0
  git diff --no-index --binary -- /dev/null "$source_file" >> "$RUN_ROOT/source.patch" || diff_status=$?
  ((diff_status <= 1)) || exit "$diff_status"
done < <(git ls-files --others --exclude-standard -z -- core/src core-batch/src realtime/src gateway/src fake-pg/src)

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

build_tasks=(:core:bootJar :gateway:bootJar :realtime:bootJar :fake-pg:bootJar)
if [[ $TOOL == gatling ]]; then
  build_tasks+=(:loadtest-gatling:gatlingDist)
elif [[ $SCENARIO != smoke ]]; then
  "$ROOT/scripts/loadtest/build-k6.sh"
elif [[ ! -x $OUT/k6 ]]; then
  "$ROOT/scripts/loadtest/build-k6.sh"
fi

if ! ./gradlew -q "${build_tasks[@]}" > "$RUN_ROOT/build.log" 2>&1; then
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
    local scouter_conf
    scouter_conf=$(scouter_config_file "$name")
    opts+=("-javaagent:$SCOUTER_AGENT_DIR/scouter.agent.jar"
      "--add-opens=java.base/java.lang=ALL-UNNAMED"
      "--add-exports=java.base/sun.net=ALL-UNNAMED"
      "-Dscouter.config=$ROOT/deploy/scouter/$scouter_conf"
      "-Dnet_collector_ip=${SCOUTER_COLLECTOR_IP:-127.0.0.1}"
      "-Dobj_name=$name" -Djdk.attach.allowAttachSelf=true)
    printf 'Scouter agent 연결: obj_name=%s, collector=%s:6100\n' "$name" "${SCOUTER_COLLECTOR_IP:-127.0.0.1}" >&2
  fi
  if $AOT_CACHE && [[ $name != fake-pg ]]; then
    jar="$AOT_DIR/$name/$name.jar"
    if [[ $AOT_PHASE == train ]]; then
      opts+=("-XX:AOTCacheOutput=$AOT_DIR/$name.aot")
    else
      # AOTMode=on은 캐시를 못 쓰면 기동을 실패시킨다(조용히 캐시 없이 뜨는 것을 막는다)
      opts+=("-XX:AOTCache=$AOT_DIR/$name.aot" "-XX:AOTMode=on" "-Xlog:aot:file=$dir/$name-aot.log")
    fi
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
    printf 'tool: %s\n' "$TOOL"
    if [[ $TOOL == gatling ]]; then
      printf 'gatling: %s, 힙 -Xmx%s(고정)\n' "$(ls "$GATLING_DIST/lib" | rg -o 'gatling-core-[0-9.]+' | head -n 1)" "$GATLING_HEAP"
      printf 'gatling-report: %s\n' "$GATLING_REPORT"
    else
      printf 'k6: %s\n' "$("$OUT/k6" version 2>&1 | head -n 1)"
    fi
    printf 'scenario: %s\nruns: %s\nfailure-inject: %s\njfr: %s\nobservability: %s\n' "$SCENARIO" "$RUNS" "$FAILURE_INJECT" "$JFR" "$OBSERVABILITY"
    if [[ $SCENARIO == seat-conflict-refund ]]; then
      printf 'conflict-scale: 100쌍(회원 200명), 첫 예매 2석·둘째 예매 2석(1석 겹침), 선점 키 유실은 첫 예매의 겹치는 좌석 1개 키만\n'
      printf 'conflict-fake-pg: 결제 실패율 0, 웹훅 중복률 0.3(복사본 중복 2)\n'
      printf 'conflict-measurement: 서버 지표·k6 요약·resources.csv는 둘째 단계(결제·확정·환불) 구간만. 첫 단계는 k6-first.log·k6-first-summary.json\n'
    fi
    printf 'k6-cpus: %s\napp-cpus: %s\n' "$K6_CPUS" "$APP_CPUS"
    printf 'vus: %s\nwarmup: %s\nwarmup-rounds: %s\nrate-limit: %s\n' "${VUS:-기본}" "$WARMUP" "$($WARMUP && echo "$WARMUP_ROUNDS" || echo 0)" "$($NO_RATE_LIMIT && echo 비활성 || echo 활성)"
    if $WARMUP; then
      printf 'warmup-fixed-load: 시나리오 기본 부하(VU %s)를 %s회, 회차마다 새 회차·좌석, 별도 멱등 키\n' "${VUS:-시나리오 기본}" "$WARMUP_ROUNDS"
      printf 'warmup-completion: 회차마다 결제 요청 뒤 PENDING 예매 0건(상한 %s초), 대기열 waiting 0·queue:active 제외(상한 %s초). 미달이면 실행 실패\n' "$WARMUP_SETTLE_TIMEOUT" "$WARMUP_QUEUE_TIMEOUT"
      printf 'warmup-jvm-restart: 없음\n'
    fi
    printf 'os: %s\n' "$(uname -srm)"
    printf 'tracing-sampling: %s\n' "$($OBSERVABILITY && echo 0.1 || echo 비활성)"
    printf 'scouter: %s\n' "$([[ -n ${SCOUTER_AGENT_DIR:-} ]] && echo 활성 || echo 비활성)"
    printf 'hikari-maximum-pool-size: %s\n' "${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:-10}"
    if $AOT_CACHE; then
      printf 'aot-cache: 사용(학습 부하 %s회)\n' "$AOT_TRAIN_ROUNDS"
      for name in core gateway realtime; do printf 'aot-cache-size-%s: %s\n' "$name" "$(stat -c %s "$AOT_DIR/$name.aot")"; done
    else
      printf 'aot-cache: 미사용\n'
    fi
    printf 'heap: core 2g, realtime 1g, gateway 1g, fake-pg 512m\n'
  } > "$1/env.txt"
}

start_apps() {
  local dir=$1 failure=$2 duplicate=$3 batch=() obs=()
  [[ $SCENARIO != hot-seats && $SCENARIO != seat-conflict-refund ]] || batch=(TICKETING_REALTIME_QUEUE_ADMISSION_BATCH_SIZE=1000)
  if $OBSERVABILITY; then obs=(TRACING_EXPORT_ENABLED=true TRACING_SAMPLING_PROBABILITY=0.1); fi
  start fake-pg "$dir" "-Xms512m -Xmx512m" fake-pg/build/libs/fake-pg.jar \
    FAKE_PG_WEBHOOK_URL=http://localhost:18080/api/v1/payments/webhooks/fake-pg \
    FAKE_PG_BEHAVIOR_FAILURE_RATE="$failure" FAKE_PG_BEHAVIOR_DUPLICATE_RATE="$duplicate" "${obs[@]}"
  start core "$dir" "-Xms2g -Xmx2g" core/build/libs/core.jar "${obs[@]}"
  start realtime "$dir" "-Xms1g -Xmx1g" realtime/build/libs/realtime.jar "${batch[@]}" "${obs[@]}"
  local gw_env=()
  if $NO_RATE_LIMIT; then gw_env=(TICKETING_GATEWAY_RATE_LIMIT_ENABLED=false); fi
  start gateway "$dir" "-Xms1g -Xmx1g" gateway/build/libs/gateway.jar "${gw_env[@]}" "${obs[@]}"
  health fake-pg 18090 "${PIDS[0]}" "$dir" || return 1
  health core 18080 "${PIDS[1]}" "$dir" || return 1
  health realtime 18081 "${PIDS[2]}" "$dir" || return 1
  health gateway 18000 "${PIDS[3]}" "$dir" || return 1
}

psql_q() { # booking 스키마 조회용. 스키마를 넘는 조인은 하지 않는다
  docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qAt -c "$1"' sh "$1"
}
redis_q() { docker compose exec -T redis redis-cli --raw "$@" | tr -d '\r'; }

# collect_metrics <출력 파일>: 앱 지표(누적 카운터)를 직접 읽는다. 관측 스택이 있으면 Prometheus 마지막 scrape 시각도 남긴다
collect_metrics() {
  local prom=()
  if $OBSERVABILITY; then prom=(--prometheus http://localhost:19090); fi
  python3 "$ROOT/scripts/loadtest/metrics-delta.py" collect "$1" "${prom[@]}" "${METRIC_TARGETS[@]}"
}

# warmup_settle <dir> <round> <schedule_id> <k6 종료 코드>: 워밍업 회차의 비동기 처리가 끝났는지 확인하고 warmup-settle-<round>.json을 남긴다.
# 결제 요청이 나간 예매가 모두 확정·취소되고, 대기열 waiting이 비며 queue:active에서 빠질 때까지 기다린다. 삭제·flush는 하지 않는다.
warmup_settle() {
  local dir=$1 round=$2 schedule_id=$3 k6_code=$4
  local started=$SECONDS pending statuses waiting active admitted_keys settled=false queue_settled=false
  while :; do
    pending=$(psql_q "SELECT count(*) FROM booking.booking WHERE schedule_id = $schedule_id AND status = 'PENDING' AND payment_requested_at IS NOT NULL")
    ((pending == 0)) && { settled=true; break; }
    ((SECONDS - started >= WARMUP_SETTLE_TIMEOUT)) && break
    sleep 1
  done
  local queue_started=$SECONDS
  while :; do
    waiting=$(redis_q ZCARD "queue:waiting:{$schedule_id}")
    active=$(redis_q SISMEMBER queue:active "$schedule_id")
    if ((waiting == 0 && active == 0)); then queue_settled=true; break; fi
    ((SECONDS - queue_started >= WARMUP_QUEUE_TIMEOUT)) && break
    sleep 1
  done
  statuses=$(psql_q "SELECT coalesce(jsonb_object_agg(status, n), '{}'::jsonb) FROM (SELECT status, count(*) AS n FROM booking.booking WHERE schedule_id = $schedule_id GROUP BY status) t")
  admitted_keys=$(redis_q --scan --pattern "queue:admitted:{$schedule_id}:*" | grep -c . || true)
  jq -n --argjson round "$round" --argjson scheduleId "$schedule_id" --argjson k6ExitCode "$k6_code" \
    --argjson pending "$pending" --argjson settled "$settled" --argjson waiting "$waiting" --argjson active "$active" \
    --argjson queueSettled "$queue_settled" --argjson statuses "$statuses" --argjson admittedKeys "$admitted_keys" \
    --argjson waitedSeconds "$((SECONDS - started))" --argjson settleLimit "$WARMUP_SETTLE_TIMEOUT" --argjson queueLimit "$WARMUP_QUEUE_TIMEOUT" '
    {round: $round, warmupScheduleId: $scheduleId, k6ExitCode: $k6ExitCode, bookingsByStatus: $statuses,
     paymentRequestedPending: $pending, paymentSettled: $settled, waitedSeconds: $waitedSeconds, settleLimitSeconds: $settleLimit,
     queueWaiting: $waiting, queueActiveMember: ($active == 1), queueSettled: $queueSettled, queueLimitSeconds: $queueLimit,
     leftoverAdmittedKeys: $admittedKeys,
     note: "leftoverAdmittedKeys는 TTL로 만료되며 키에 회차 ID가 들어 있어 본 회차와 겹치지 않는다(삭제하지 않음)."}' > "$dir/warmup-settle-$round.json"
  $settled && $queue_settled
}

# 워밍업 회차와 본 측정 회차의 격리를 확인한다(회차 ID·멱등 키가 겹치지 않음). 결과: isolation.json
check_isolation() {
  local dir=$1 measure_id=$2 warm_ids=$3 overlap
  overlap=$(psql_q "SELECT count(*) FROM (SELECT idempotency_key FROM booking.booking WHERE schedule_id = $measure_id INTERSECT SELECT idempotency_key FROM booking.booking WHERE schedule_id IN ($warm_ids)) t")
  jq -n --argjson measure "$measure_id" --argjson warm "[$warm_ids]" --argjson overlap "$overlap" '
    {measurementScheduleId: $measure, warmupScheduleIds: $warm, scheduleIdsDistinct: ($warm | index($measure) == null),
     bookingIdempotencyKeyOverlap: $overlap, isolated: (($warm | index($measure) == null) and $overlap == 0)}' > "$dir/isolation.json"
  [[ $(jq -r .isolated "$dir/isolation.json") == true ]]
}

# 보상 시나리오(seat-conflict-refund)의 규모. loadtest/lib/conflict.ts의 PAIR_COUNT, seed.sh의 conflictPairs와 같아야 한다
CONFLICT_PAIRS=100

# conflict_first_phase <dir> <schedule_file>: 첫 단계(A 회원 100명이 PENDING 예매)를 실행하고, 그 예매의 겹치는 좌석 선점 키만 지운다.
# 지우는 키는 부하 전용 회차의 키 중 값(소유자)이 방금 만든 예매의 hold_token인 것뿐이다. DB 제약·판매 행은 건드리지 않는다.
conflict_first_phase() {
  local dir=$1 schedule_file=$2 schedule_id members_min first_status=0 created
  schedule_id=$(jq -r .scheduleId "$schedule_file")
  members_min=$(jq -r .userIdMin "$OUT/members.json")
  env PHASE=first BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$schedule_file" \
    READINESS_FILE="$dir/conflict-first-readiness.json" taskset -c "$K6_CPUS" "$OUT/k6" run --summary-export "$dir/k6-first-summary.json" \
    loadtest/scenarios/seat-conflict-refund.ts > "$dir/k6-first.log" 2>&1 || first_status=$?
  if ((first_status != 0 && first_status != 99)) || rg -q 'source=stacktrace|script exception' "$dir/k6-first.log"; then
    echo "첫 단계 k6 오류(종료 코드 $first_status): $dir/k6-first.log" >&2
    return 1
  fi
  psql_q "SELECT coalesce(jsonb_agg(jsonb_build_object('pair', member_id - $members_min + 1, 'memberId', member_id, 'bookingId', id, 'holdToken', hold_token) ORDER BY member_id), '[]'::jsonb)
    FROM booking.booking WHERE schedule_id = $schedule_id AND status = 'PENDING' AND member_id BETWEEN $members_min AND $((members_min + CONFLICT_PAIRS - 1))" > "$dir/conflict-first.json"
  created=$(jq length "$dir/conflict-first.json")
  if ((created != CONFLICT_PAIRS)); then
    echo "첫 단계 PENDING 예매가 $created건입니다(기대 $CONFLICT_PAIRS). $dir/conflict-first.json, $dir/k6-first.log 참고" >&2
    return 1
  fi
  # 그룹마다 겹치는 좌석의 선점 키를 읽어 소유자가 첫 예매의 hold_token일 때만 지운다
  local keys_file="$dir/conflict-injection-keys.tsv"
  jq -r --slurpfile first "$dir/conflict-first.json" --argjson sid "$schedule_id" \
    '.conflictPairs[] | . as $p | ($first[0][] | select(.pair == $p.pair)) as $f | [$p.pair, "booking:hold:{\($sid)}:\($p.overlapSeatId)", $f.holdToken] | @tsv' "$schedule_file" > "$keys_file"
  local owners deleted=0 mismatched=0 pair key token owner index=0
  mapfile -t owners < <(cut -f2 "$keys_file" | sed 's/^/GET /' | docker compose exec -T redis redis-cli --raw | tr -d '\r')
  local del_cmds=()
  while IFS=$'\t' read -r pair key token; do
    owner=${owners[$index]:-}
    index=$((index + 1))
    if [[ $owner == "$token" ]]; then del_cmds+=("DEL $key"); else mismatched=$((mismatched + 1)); fi
  done < "$keys_file"
  if ((${#del_cmds[@]})); then
    deleted=$(printf '%s\n' "${del_cmds[@]}" | docker compose exec -T redis redis-cli --raw | tr -d '\r' | awk '{n += $1} END {print n + 0}')
  fi
  jq -n --argjson scheduleId "$schedule_id" --argjson targets "$CONFLICT_PAIRS" --argjson deleted "$deleted" --argjson ownerMismatch "$mismatched" \
    '{scheduleId: $scheduleId, targetKeys: $targets, deletedHoldKeys: $deleted, ownerMismatch: $ownerMismatch,
      note: "첫 예매의 겹치는 좌석 선점 키만 삭제(소유자=hold_token 확인). DB sold_seat·유니크 제약은 조작하지 않음"}' > "$dir/conflict-injection.json"
  ((deleted == CONFLICT_PAIRS)) || { echo "선점 키 유실 주입이 $deleted/$CONFLICT_PAIRS건입니다: $dir/conflict-injection.json" >&2; return 1; }
}

# conflict_manifest <dir> <schedule_file>: 둘째 단계 뒤 예매 ID를 회원으로 대응시켜 실행 manifest를 만든다. 짝이 빠진 그룹이 있으면 1을 반환한다
conflict_manifest() {
  local dir=$1 schedule_file=$2 schedule_id members_min bookings
  schedule_id=$(jq -r .scheduleId "$schedule_file")
  members_min=$(jq -r .userIdMin "$OUT/members.json")
  bookings=$(psql_q "SELECT coalesce(jsonb_agg(jsonb_build_object('memberId', member_id, 'id', id)), '[]'::jsonb) FROM booking.booking WHERE schedule_id = $schedule_id")
  jq -n --argjson bookings "$bookings" --argjson min "$members_min" --argjson n "$CONFLICT_PAIRS" --argjson sid "$schedule_id" --slurpfile sched "$schedule_file" '
    ($bookings | map({key: (.memberId | tostring), value: .id}) | from_entries) as $byMember
    | [$sched[0].conflictPairs[] | . as $p
       | {pair: $p.pair, memberA: ($min + $p.pair - 1), memberB: ($min + $n + $p.pair - 1), seatsA: $p.seatsA, seatsB: $p.seatsB, overlapSeatId: $p.overlapSeatId}
       | . + {bookingA: $byMember[(.memberA | tostring)], bookingB: $byMember[(.memberB | tostring)]}] as $all
    | {scheduleId: $sid, scale: {pairs: $n, members: ($n * 2), seatsPerBooking: 2, overlappingSeatsPerPair: 1},
       expectedPairs: $n, completePairs: ($all | map(select(.bookingA != null and .bookingB != null)) | length),
       pairs: ($all | map(select(.bookingA != null and .bookingB != null)))}' > "$dir/conflict-manifest.json"
  [[ $(jq '.completePairs == .expectedPairs' "$dir/conflict-manifest.json") == true ]]
}

SUMMARY=()
run_once() {
  local n=$1 dir="$RUN_ROOT/$1"
  mkdir -p "$dir"
  write_env "$dir"
  local failure=0 duplicate=0
  if $FAILURE_INJECT; then failure=0.1; duplicate=0.3; fi
  # 보상 검증은 결제 실패와 좌석 충돌 환불을 구분하려고 실패율 0, 웹훅 중복 30%로 고정한다
  if [[ $SCENARIO == seat-conflict-refund ]]; then failure=0; duplicate=0.3; fi

  start_apps "$dir" "$failure" "$duplicate" || return 1

  "$ROOT/scripts/loadtest/seed.sh" members > "$dir/seed.log" 2>&1 || { echo "회원 시드 실패: $dir/seed.log" >&2; return 1; }
  local schedule_file="$OUT/schedule-$SCENARIO.json"
  if [[ $SCENARIO != smoke ]]; then
    "$ROOT/scripts/loadtest/seed.sh" schedule --scenario "$SCENARIO" >> "$dir/seed.log" 2>&1 \
      || { echo "회차 시드 실패: $dir/seed.log" >&2; return 1; }
  fi

  local warm_ok=true warm_ids= server_delta_ok=true
  if $WARMUP; then
    # 콜드 스타트(클래스 로딩·JIT·연결 생성)를 걷어내려고 같은 앱(재시작 없음)에 별도 회차로 먼저 같은 부하를 N회 보낸다.
    # 회차마다 새 공연 회차·좌석을 시드하고 멱등 키는 요청마다 새로 만든다. 서버 지표 사전값은 워밍업 직전에 직접 수집한다.
    collect_metrics "$dir/server-metrics-pre-warmup.json" || { echo "워밍업 전 지표 수집 실패: $dir/server-metrics-pre-warmup.json" >&2; return 1; }
    local warm_vus=() round warm_schedule warm_status
    [[ -z $VUS ]] || warm_vus=(VUS="$VUS")
    for ((round = 1; round <= WARMUP_ROUNDS; round++)); do
      warm_schedule=
      [[ $SCENARIO == smoke ]] || warm_schedule=$(jq -r .scheduleId "$schedule_file")
      warm_status=0
      env BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$schedule_file" \
        READINESS_FILE="$dir/warmup-readiness-$round.json" \
        "${warm_vus[@]}" taskset -c "$K6_CPUS" "$OUT/k6" run --summary-export "$dir/warmup-k6-summary-$round.json" "loadtest/scenarios/$SCENARIO.ts" > "$dir/warmup-k6-$round.log" 2>&1 || warm_status=$?
      if [[ $SCENARIO != smoke ]]; then
        warm_ids="${warm_ids:+$warm_ids,}$warm_schedule"
        if ! warmup_settle "$dir" "$round" "$warm_schedule" "$warm_status"; then
          warm_ok=false
          echo "워밍업 $round회 비동기 처리가 상한 안에 끝나지 않았습니다: $dir/warmup-settle-$round.json" >&2
        fi
        "$ROOT/scripts/loadtest/seed.sh" schedule --scenario "$SCENARIO" >> "$dir/seed.log" 2>&1 \
          || { echo "워밍업 $round회 뒤 회차 시드 실패: $dir/seed.log" >&2; return 1; }
      elif ((warm_status != 0)); then
        warm_ok=false
        echo "smoke 워밍업 $round회 실패(종료 코드 $warm_status): $dir/warmup-k6-$round.log" >&2
      fi
    done
    $warm_ok || return 1
  fi

  local scenario_env=()
  if [[ $SCENARIO == seat-conflict-refund ]]; then
    conflict_first_phase "$dir" "$schedule_file" || return 1
    scenario_env=(PHASE=second FIRST_FILE="$dir/conflict-first.json")
  fi
  local k6_args=(run --summary-export "$dir/k6-summary.json")
  if $OBSERVABILITY; then k6_args+=(-o experimental-prometheus-rw); fi
  local k6_status=0 vus_env=()
  [[ -z $VUS ]] || vus_env=(VUS="$VUS")
  # 부하 도구별 로그·요약 파일. gatling은 k6 요약과 같은 형태의 JSON을 SUMMARY_FILE로 남긴다
  local load_log="$dir/$TOOL.log" load_summary="$dir/$TOOL-summary.json"
  local k6_from k6_to
  # 본 부하 직전 직접 수집(서버 지표 사전값). 워밍업이 있으면 워밍업과 시드 요청은 여기까지의 값에 들어간다
  collect_metrics "$dir/server-metrics-pre.json" || { echo "본 측정 전 지표 수집 실패: $dir/server-metrics-pre.json" >&2; return 1; }
  k6_from=$(date +%s)
  printf 'k6_start_unix_seconds: %s\n' "$k6_from" > "$dir/measurement.txt"
  if [[ $TOOL == gatling ]]; then
    local simulation=QueueSurgeSimulation
    local report_args=()
    $GATLING_REPORT || report_args=(-nr)
    [[ $SCENARIO != hot-seats ]] || simulation=HotSeatsSimulation
    # 기본 사용자 홈이 읽기 전용이어도 Preferences의 잠금·저장이 가능하도록 회차별로 격리한다.
    mkdir -p "$dir/gatling-prefs/.java/.userPrefs"
    # taskset이 java를 exec하므로 $!가 곧 Gatling JVM이다(RSS 샘플링 대상)
    env BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$schedule_file" \
      READINESS_FILE="$dir/readiness.json" SUMMARY_FILE="$load_summary" "${vus_env[@]}" "${scenario_env[@]}" \
      taskset -c "$K6_CPUS" java -Xmx"$GATLING_HEAP" "-Xlog:gc*:file=$dir/gatling-gc.log" -XX:+HeapDumpOnOutOfMemoryError "-XX:HeapDumpPath=$dir/gatling.hprof" \
      --enable-native-access=ALL-UNNAMED "-Djava.util.prefs.userRoot=$dir/gatling-prefs" \
      --add-opens java.base/java.lang=ALL-UNNAMED -cp "$GATLING_DIST/lib/*" io.gatling.app.Gatling \
      -s "io.github.ticketing.loadtest.$simulation" -rf "$dir/gatling-results" "${report_args[@]}" > "$load_log" 2>&1 &
  else
    env K6_PROMETHEUS_RW_SERVER_URL=http://localhost:19090/api/v1/write K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99),avg,max' \
      BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$schedule_file" \
      READINESS_FILE="$dir/readiness.json" "${vus_env[@]}" "${scenario_env[@]}" taskset -c "$K6_CPUS" "$OUT/k6" "${k6_args[@]}" "loadtest/scenarios/$SCENARIO.ts" > "$load_log" 2>&1 &
  fi
  local k6_pid=$!
  python3 "$ROOT/scripts/loadtest/sample-resources.py" "$k6_pid" "$dir/resources.csv" > "$dir/resource-sampler.log" 2>&1 &
  SAMPLER_PID=$!
  "$ROOT/scripts/loadtest/sample-pg.sh" "$k6_pid" "$dir/pg-activity.log" &
  PG_SAMPLER_PID=$!
  wait "$k6_pid" || k6_status=$?
  k6_to=$(date +%s)
  printf 'k6_end_unix_seconds: %s\nk6_exit_code: %s\n' "$k6_to" "$k6_status" >> "$dir/measurement.txt"
  # 본 부하 직후 직접 수집(서버 지표 사후값)
  collect_metrics "$dir/server-metrics-post.json" || { server_delta_ok=false; echo "본 측정 후 지표 수집 실패: $dir/server-metrics-post.json" >&2; }
  wait "$SAMPLER_PID" || true
  wait "$PG_SAMPLER_PID" || true
  SAMPLER_PID=

  # 서버 지표 차분: 본 부하 직전·직후 직접 수집값의 차이만 쓴다(워밍업·시드 요청 제외)
  local delta_status=0
  if $server_delta_ok; then
    python3 "$ROOT/scripts/loadtest/metrics-delta.py" diff "$dir/server-metrics-pre.json" "$dir/server-metrics-post.json" "$dir/server-delta.json" \
      --scenario "$SCENARIO" --k6-summary "$load_summary" --label measurement > "$dir/server-delta.log" 2>&1 || delta_status=$?
    ((delta_status != 2)) || { server_delta_ok=false; echo "서버 지표 차분 오류(reset·누락): $dir/server-delta.log" >&2; }
    if ((delta_status == 1)); then echo "통제 입력 불일치: $dir/server-delta.log" >&2; fi
  fi
  printf 'server_delta_valid: %s\nserver_delta_controlled_inputs: %s\n' "$($server_delta_ok && echo yes || echo no)" "$( ((delta_status == 1)) && echo 불일치 || echo 일치·판정불가)" >> "$dir/measurement.txt"
  if $WARMUP; then
    # 워밍업 구간(워밍업 직전~본 부하 직전) 차분은 워밍업 요청이 이 구간에만 들어갔음을 확인하는 통제 입력이다
    if [[ $SCENARIO != smoke ]]; then
      jq -s '{metrics: {flow_booking_attempt: {count: (map(.metrics.flow_booking_attempt.count // 0) | add)}, http_reqs: {count: (map(.metrics.http_reqs.count // 0) | add)}}}' \
        "$dir"/warmup-k6-summary-*.json > "$dir/warmup-k6-summary-total.json" 2>/dev/null || true
    else
      jq -s '{metrics: {http_reqs: {count: (map(.metrics.http_reqs.count // 0) | add)}}}' "$dir"/warmup-k6-summary-*.json > "$dir/warmup-k6-summary-total.json" 2>/dev/null || true
    fi
    python3 "$ROOT/scripts/loadtest/metrics-delta.py" diff "$dir/server-metrics-pre-warmup.json" "$dir/server-metrics-pre.json" "$dir/server-delta-warmup.json" \
      --scenario "$SCENARIO" --k6-summary "$dir/warmup-k6-summary-total.json" --label warmup > "$dir/server-delta-warmup.log" 2>&1 || true
  fi
  if $OBSERVABILITY; then
    sleep 6 # 마지막 scrape(5초 간격)가 Prometheus에 들어오게 한다
    local snapshot_to=$k6_to
    ((snapshot_to > k6_from)) || snapshot_to=$((k6_from + 1)) # 1초 미만 실행(smoke)도 구간이 비지 않게 한다
    "$ROOT/scripts/loadtest/prom-snapshot.sh" "$k6_from" "$snapshot_to" "$dir" > "$dir/prom-snapshot.log" 2>&1 \
      || echo "Prometheus 지표 추출 실패: $dir/prom-snapshot.log" >&2
    if $WARMUP && [[ -f $dir/prometheus.json ]]; then
      # 구간 안 마지막 누적값(last_over_time)에는 워밍업 요청이 섞이므로 워밍업 계열에서는 제거한다. 차분은 server-delta.json을 쓴다
      jq '.http |= map({application, uri, rpsMax}) | del(.gatewayRouteBuckets, .hikariAcquireBuckets, .queueMetrics, .seatCopyCache, .gatewayStages, .bookingCreateStages, .gatewayRoutePost, .gcPauseSeconds)
        | .gatewayConnectionPool |= del(.pendingTimeBuckets) | .hikari |= map_values(del(.acquireCount, .acquireAvgSeconds, .timeouts)) | .virtualThreads |= map_values(del(.pinnedEvents))
        | .warmupSeries = "워밍업 계열: 누적 히스토그램·카운터 항목은 server-delta.json의 직접 수집 차분을 쓴다. 남은 값(rps·게이지 최대·cpu·heap)은 측정 구간 5초 표본이다."' \
        "$dir/prometheus.json" > "$dir/prometheus.json.tmp" && mv "$dir/prometheus.json.tmp" "$dir/prometheus.json"
    fi
  fi

  local compensation='해당 없음' compensation_ok=true
  if [[ $SCENARIO == seat-conflict-refund ]]; then
    # 환불은 비동기라 완료 조건과 상한(COMPENSATION_TIMEOUT, 기본 120초)을 두고 기다린다. 원장은 실패해도 보존한다
    if conflict_manifest "$dir" "$schedule_file" && "$ROOT/scripts/loadtest/verify-compensation.sh" "$(jq -r .scheduleId "$schedule_file")" "$dir/conflict-manifest.json" "$dir" "$dir/fake-pg.log" > "$dir/compensation.log" 2>&1; then
      compensation='통과'
    else
      compensation='위반·미완료(compensation.log, compensation.json 참고)'
      compensation_ok=false
      [[ -f $dir/compensation.json ]] || "$ROOT/scripts/loadtest/verify-compensation.sh" "$(jq -r .scheduleId "$schedule_file")" "$dir/conflict-manifest.json" "$dir" "$dir/fake-pg.log" >> "$dir/compensation.log" 2>&1 || true
    fi
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

  local isolation='해당 없음' isolation_ok=true
  if $WARMUP && [[ $SCENARIO != smoke ]]; then
    if check_isolation "$dir" "$(jq -r .scheduleId "$schedule_file")" "$warm_ids"; then isolation='격리 확인'; else isolation='격리 위반(isolation.json 참고)'; isolation_ok=false; fi
  fi
  # 시나리오 B: 1,000명 동시 경합 도달 여부. 미달·지각은 실패 근거로 남기며 합격 근거로 쓰지 않는다
  local readiness='해당 없음'
  if [[ $SCENARIO == hot-seats && -f $dir/readiness.json ]]; then
    readiness=$(jq -r '"정시 준비 \(.readyOnTime // "기록 없음")/\(.vus), 지각 \(.readyLate // "기록 없음"), 예매 시도 \(.bookingAttempt // "기록 없음"), 동시 경합 \(if .fullContention then "충족" else "미충족(합격 근거 아님)" end)"' "$dir/readiness.json")
    printf 'b_readiness: %s\n' "$readiness" >> "$dir/measurement.txt"
  fi

  if [[ $SCENARIO == seat-conflict-refund && -f $dir/readiness.json ]]; then
    readiness=$(jq -r '"B 회원 정시 준비 \(.readyOnTime // "기록 없음")/\(.vus), 지각 \(.readyLate // "기록 없음"), 예매 시도 \(.bookingAttempt // "기록 없음")"' "$dir/readiness.json")
    printf 'conflict_readiness: %s\n' "$readiness" >> "$dir/measurement.txt"
  fi

  cleanup # SIGTERM으로 종료해 JFR이 파일로 남게 한다

  # k6 종료 코드 99, gatling 종료 코드 2는 threshold(assertion) 미달이다. 기준 측정은 미달 항목을 그대로 기록하므로 스크립트 실패로 보지 않는다.
  # smoke의 99는 checks 실패(연결 이상)라서 실패로 본다
  local thresholds='통과' script_ok=true reqs failed p95 tool_rss_mb host_mem_peak
  local threshold_code=99
  [[ $TOOL != gatling ]] || threshold_code=2
  if ((k6_status == threshold_code)) && [[ $SCENARIO != smoke ]]; then
    thresholds="미달($TOOL 종료 코드 $threshold_code, 기록용이며 실패 아님)"
  elif ((k6_status != 0)); then
    thresholds="스크립트 오류($TOOL 종료 코드 $k6_status, $TOOL.log 참고)"
    script_ok=false
  fi
  if [[ $TOOL == gatling ]]; then
    # 시뮬레이션 안의 예외는 종료 코드에 드러나지 않을 수 있어 로그에서 찾는다
    if rg -q 'Exception in thread|OutOfMemoryError' "$load_log"; then
      thresholds="$thresholds, 스크립트 예외 발생(gatling.log 참고)"
      script_ok=false
    fi
    reqs='n/a(리포트 생략)'
    if $GATLING_REPORT; then
      reqs='n/a(HTML 리포트 참고)'
      local report_file report_found=false
      for report_file in "$dir"/gatling-results/*/index.html; do
        if [[ -s $report_file ]]; then
          SUMMARY+=("run $n: Gatling HTML 리포트 $report_file")
          report_found=true
        fi
      done
      if ! $report_found; then
        thresholds="$thresholds, HTML 리포트 생성 실패(gatling.log 참고)"
        script_ok=false
      fi
    fi
    failed=$(rg -o 'Global: percentage of failed events.*actual : [0-9.]+' "$load_log" | head -n 1 || true)
    p95=$(rg -o 'booking_create: 95th percentile.*actual : [0-9.]+' "$load_log" | rg -o 'actual : [0-9.]+' | head -n 1 || true)
    : "${failed:=n/a}" "${p95:=n/a}"
  else
    # 반복 중 던져진 스크립트 예외는 k6 종료 코드에 드러나지 않으므로 로그에서 찾는다
    if rg -q 'source=stacktrace|script exception' "$load_log"; then
      thresholds="$thresholds, 스크립트 예외 발생(k6.log의 source=stacktrace 참고)"
      script_ok=false
    fi
    reqs=$(jq -r '.metrics.http_reqs.count // "n/a"' "$load_summary" 2>/dev/null || echo n/a)
    failed=$(jq -r '.metrics.http_req_failed | (.value // .rate) // "n/a"' "$load_summary" 2>/dev/null || echo n/a)
    p95=$(jq -r '.metrics["http_req_duration{name:booking_create}"]["p(95)"] // "n/a"' "$load_summary" 2>/dev/null || echo n/a)
  fi
  if $WARMUP && ! $server_delta_ok; then script_ok=false; fi
  # 앱·인프라·부하 도구가 한 호스트에서 돌므로 호스트 전체 메모리 사용률 최대값도 남긴다(총량 대비 MemAvailable 최소값)
  host_mem_peak=$(awk -F, 'NR > 1 && $5 + 0 > 0 {used = 1 - ($6 + 0) / ($5 + 0); if (used > max) max = used} END {if (max > 0) printf "%.1f%%", max * 100; else print "n/a"}' "$dir/resources.csv" 2>/dev/null || echo n/a)
  # 부하 도구 프로세스의 최대 RSS(MB). k6와 gatling을 같은 방식으로 비교하는 지표다
  tool_rss_mb=$(awk -F, 'NR > 1 && $3 + 0 > max {max = $3 + 0} END {if (max > 0) printf "%.0f", max / 1048576; else print "n/a"}' "$dir/resources.csv" 2>/dev/null || echo n/a)
  SUMMARY+=("run $n: 도구 $TOOL, thresholds $thresholds, 부하 도구 최대 RSS ${tool_rss_mb}MB, 호스트 메모리 최대 사용률 ${host_mem_peak}, 요청 $reqs, http_req_failed $failed, 예매 생성 p95 $p95, 정합성 $integrity, 워밍업 $($WARMUP && echo "$WARMUP_ROUNDS회, $isolation" || echo 없음), 서버 차분 $($server_delta_ok && echo 유효 || echo 오류), B 준비 $readiness, 보상 $compensation, 디렉터리 $dir")
  $script_ok && $integrity_ok && $isolation_ok && $compensation_ok
}

AOT_DIR="$RUN_ROOT/aot"
AOT_PHASE=use
# 중첩 jar는 AOT 캐시 대상이 안 되므로 jar를 풀고, 학습 기동에서 캐시를 만든 뒤 측정 기동에서 쓴다
prepare_aot() {
  mkdir -p "$AOT_DIR"
  local name
  for name in core gateway realtime; do
    java -Djarmode=tools -jar "$name/build/libs/$name.jar" extract --destination "$AOT_DIR/$name" > "$AOT_DIR/extract-$name.log" 2>&1 \
      || { echo "$name jar 풀기 실패: $AOT_DIR/extract-$name.log" >&2; return 1; }
    [[ -f $AOT_DIR/$name/$name.jar ]] || { echo "$name 풀린 jar를 찾지 못했습니다: $AOT_DIR/$name" >&2; return 1; }
  done
  AOT_PHASE=train
  local dir="$AOT_DIR/train"
  mkdir -p "$dir"
  start_apps "$dir" 0 0 || return 1
  "$ROOT/scripts/loadtest/seed.sh" members > "$dir/seed.log" 2>&1 || { echo "학습 회원 시드 실패: $dir/seed.log" >&2; return 1; }
  local schedule_file="$OUT/schedule-$SCENARIO.json" round vus_env=()
  [[ -z $VUS ]] || vus_env=(VUS="$VUS")
  for ((round = 1; round <= AOT_TRAIN_ROUNDS; round++)); do
    "$ROOT/scripts/loadtest/seed.sh" schedule --scenario "$SCENARIO" >> "$dir/seed.log" 2>&1 || { echo "학습 회차 시드 실패: $dir/seed.log" >&2; return 1; }
    env BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$schedule_file" \
      "${vus_env[@]}" taskset -c "$K6_CPUS" "$OUT/k6" run --summary-export "$dir/k6-summary-$round.json" "loadtest/scenarios/$SCENARIO.ts" > "$dir/k6-$round.log" 2>&1 || true
  done
  cleanup # SIGTERM으로 정상 종료해야 캐시가 써진다. wait가 캐시 생성이 끝날 때까지 기다린다
  for name in core gateway realtime; do
    [[ -s $AOT_DIR/$name.aot ]] || { echo "AOT 캐시가 만들어지지 않았습니다: $AOT_DIR/$name.aot (로그: $dir/$name.log)" >&2; return 1; }
  done
  AOT_PHASE=use
}

overall=0
$AOT_CACHE && { prepare_aot || { cleanup; exit 1; }; }
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
