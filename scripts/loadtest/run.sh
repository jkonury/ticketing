#!/usr/bin/env bash
# 부하 테스트 실행기(ADR 0024). 사용은 --help 참고
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
source "$ROOT/scripts/lib/scouter.sh"

source "$ROOT/scripts/loadtest/lib/args.sh"
source "$ROOT/scripts/loadtest/lib/aot.sh"
source "$ROOT/scripts/loadtest/lib/gatling.sh"
source "$ROOT/scripts/loadtest/lib/infra.sh"
source "$ROOT/scripts/loadtest/lib/warmup.sh"
source "$ROOT/scripts/loadtest/lib/scenario-conflict.sh"
source "$ROOT/scripts/loadtest/lib/metrics.sh"
source "$ROOT/scripts/loadtest/lib/scenario-checks.sh"
source "$ROOT/scripts/loadtest/lib/run-once.sh"

SCENARIO=
RUNS=1
FAILURE_INJECT=false
JFR=false
CODELIST=false
RESTART_AFTER=
CODELIST_DELAY=52
JVM_OPTS=
OBSERVABILITY=false
KEEP=false
VUS=
K6_CPUS=0-3
APP_CPUS=4-15
NO_SCOUTER=false
WARMUP=false
INTERNAL_WARMUP=false
WARMUP_ROUNDS=5
# 워밍업 한 회차의 비동기 처리 완료 대기 상한(초)과 대기열 정리 대기 상한(초). 넘으면 그 실행은 실패로 둔다
WARMUP_SETTLE_TIMEOUT=120
WARMUP_QUEUE_TIMEOUT=30
METRIC_TARGETS=(core=18080 realtime=18081 gateway=18000 fake-pg=18090 payment=18085)
AOT_CACHE=false
AOT_TRAIN_ROUNDS=15
NO_RATE_LIMIT=false
TOOL=k6
GATLING_HEAP=2g
BOOKING_P95_MS=
GATLING_REPORT=false
COUPON_STRATEGY=redis
GATLING_DIST="$ROOT/loadtest/gatling/build/gatling-dist"
parse_args "$@"
validate_args

for command in docker curl java jq taskset; do
  command -v "$command" >/dev/null || { printf '필요한 도구가 없습니다: %s\n' "$command" >&2; exit 1; }
done
[[ -f .env ]] || { echo '.env 파일이 필요합니다.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Docker가 실행 중이어야 합니다.' >&2; exit 1; }
for port in 18000 18080 18081 18085 18090; do
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
done < <(git ls-files --others --exclude-standard -z -- core/src core-batch/src payment/src realtime/src gateway/src fake-pg/src loadtest/gatling scripts/loadtest scripts/lib observability)

PIDS=()
SAMPLER_PID=
TCP_SAMPLER_PID=
LOAD_PID=
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

build_tasks=(:core:bootJar :gateway:bootJar :realtime:bootJar :fake-pg:bootJar :payment:bootJar)
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
services=(postgres redis kafka)
if $OBSERVABILITY; then
  compose+=(-f compose.yaml -f compose.observability.yaml)
  services+=(prometheus tempo grafana redis-exporter postgres-exporter loadtest-exporter)
fi
"${compose[@]}" up -d --wait "${services[@]}" > "$RUN_ROOT/compose.log" 2>&1 \
  || { echo "Compose 기동 실패: $RUN_ROOT/compose.log" >&2; tail -n 30 "$RUN_ROOT/compose.log" >&2; exit 1; }
# 기존 볼륨에는 payment_db가 없을 수 있으므로 매 실행마다 멱등하게 준비한다
"$ROOT/scripts/db/ensure-payment-db.sh" > "$RUN_ROOT/ensure-payment-db.log" 2>&1 \
  || { echo "payment_db 준비 실패: $RUN_ROOT/ensure-payment-db.log" >&2; tail -n 30 "$RUN_ROOT/ensure-payment-db.log" >&2; exit 1; }

# 보상 시나리오(seat-conflict-refund)의 규모. loadtest/lib/conflict.ts의 PAIR_COUNT, seed.sh의 conflictPairs와 같아야 한다
CONFLICT_PAIRS=100

SUMMARY=()

AOT_DIR="$RUN_ROOT/aot"
AOT_PHASE=use
overall=0
$AOT_CACHE && { prepare_aot || { cleanup; exit 1; }; }
for ((i = 1; i <= RUNS; i++)); do
  if ! run_once "$i"; then
    overall=1
    cleanup
    publish_dashboard "$RUN_ROOT/$i" failed --round "$($WARMUP && echo "$WARMUP_ROUNDS" || echo 0)" || true
    SUMMARY+=("run $i: 실패(위 메시지와 $RUN_ROOT/$i 참고)")
  fi
done

echo '=== 부하 테스트 요약 ==='
printf '%s\n' "${SUMMARY[@]}"
if $KEEP; then echo '--keep: compose 인프라는 그대로 둡니다(앱만 정리).'; fi
exit "$overall"
