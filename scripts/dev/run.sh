#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
source "$ROOT/scripts/lib/scouter.sh"
run_dir="$ROOT/build/dev/runs"
usage() {
  cat <<'HELP'
사용법: ./scripts/dev/run.sh [--all] [--infra] [--fake-pg] [--observability] [--core] [--payment] [--settlement] [--realtime] [--gateway] [--web] [--slow-queue] [--reuse-jar]
        ./scripts/dev/run.sh --stop
옵션 없이 실행하면 전체 개발 스택을 기동합니다.
--infra: PostgreSQL, Redis, Kafka, RustFS 오브젝트 저장소(기동 뒤 결제·정산 DB와 포스터 버킷 준비)  --fake-pg: 가짜 PG(호스트 jar)  --observability: Prometheus, Tempo, Grafana
--payment: 결제 앱(18085, 웹훅은 fake-pg가 직접 호출). 결제 흐름에는 --core와 --payment를 함께 띄운다
--settlement: 정산 앱(18086). 기존 데이터가 있는 환경은 런북의 정산 DB 이전 절차를 먼저 진행한다
--slow-queue: 대기열 입장을 5초에 1명으로 늦춘다(대기 순번 화면 확인용, realtime에 적용. 예: --all --slow-queue)
--reuse-jar: 선택한 Java 앱의 기존 bootJar를 실행합니다. 기동 대상이 생략되면 전체 스택을 선택합니다.
--stop: 이 작업 트리에서 run.sh가 기록한 모든 앱을 종료합니다. Docker 컨테이너는 유지됩니다.
앱 옵션은 조합할 수 있습니다. 의존 인프라는 별도로 선택해야 합니다.
Java 앱은 Scouter를 기본으로 연결합니다. .env 또는 환경 변수에 SCOUTER_AGENT_DIR을 지정하세요.
SCOUTER_COLLECTOR_IP는 기본값 127.0.0.1입니다.
앱은 이 터미널에서 관리하며 Ctrl+C로 종료합니다. Docker 컨테이너는 유지됩니다.
HELP
}

process_start_time() {
  local stat
  local -a fields
  IFS= read -r stat 2>/dev/null < "/proc/$1/stat" || return 1
  # 프로세스 이름에 공백이나 괄호가 들어갈 수 있어 마지막 닫는 괄호 뒤부터 읽는다.
  read -r -a fields <<< "${stat##*) }"
  [[ "${fields[0]:-Z}" != Z && -n "${fields[19]:-}" ]] || return 1
  printf '%s\n' "${fields[19]}"
}

stop_runs() {
  local record pid started deadline
  local -a stopping=()
  for record in "$run_dir"/*.pid; do
    [[ -f "$record" && -O "$record" ]] || continue
    pid=${record##*/}
    pid=${pid%.pid}
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || continue
    IFS= read -r started 2>/dev/null < "$record" || continue
    [[ -n "$started" && "$(process_start_time "$pid")" == "$started" ]] || continue
    kill -TERM "$pid" 2>/dev/null || true
    stopping+=("$pid $started")
  done
  if ((${#stopping[@]} == 0)); then
    echo '종료할 개발 앱이 없습니다.'
    return 0
  fi

  deadline=$((SECONDS + 30))
  local status=0
  for record in "${stopping[@]}"; do
    read -r pid started <<< "$record"
    while [[ "$(process_start_time "$pid")" == "$started" ]]; do
      if ((SECONDS >= deadline)); then
        printf '30초 안에 종료되지 않은 개발 실행이 있습니다 (PID %s).\n' "$pid" >&2
        status=1
        break
      fi
      sleep 0.1
    done
  done
  ((status == 0)) && echo '개발 앱을 종료했습니다.'
  return "$status"
}

if [[ "${1:-}" == --stop && $# == 1 ]]; then
  stop_runs
  exit
fi

infra=false; fakepg=false; observability=false; core=false; payment=false; settlement=false; realtime=false; gateway=false; web=false; slow_queue=false
reuse_jar=false
if (($# == 0)); then set -- --all; fi
for arg in "$@"; do
  case "$arg" in
    --all) infra=true; fakepg=true; core=true; payment=true; settlement=true; realtime=true; gateway=true; web=true ;;
    --infra) infra=true ;;
    --fake-pg) fakepg=true ;;
    --observability) observability=true ;;
    --core) core=true ;;
    --payment) payment=true ;;
    --settlement) settlement=true ;;
    --realtime) realtime=true ;;
    --gateway) gateway=true ;;
    --web) web=true ;;
    --slow-queue) slow_queue=true ;;
    --reuse-jar) reuse_jar=true ;;
    --stop) echo '--stop은 단독으로 사용하세요.' >&2; exit 1 ;;
    -h|--help) usage; exit 0 ;;
    *) printf '알 수 없는 옵션: %s\n' "$arg" >&2; usage >&2; exit 1 ;;
  esac
done
if $reuse_jar && ! { $infra || $fakepg || $observability || $core || $payment || $settlement || $realtime || $gateway || $web; }; then
  infra=true; fakepg=true; core=true; payment=true; settlement=true; realtime=true; gateway=true; web=true
fi

tasks=()
missing_tasks=()
for app in fake-pg core payment settlement realtime gateway; do
  selected=${app/-/}
  "${!selected}" || continue
  task=":$app:bootJar"
  tasks+=("$task")
  jar="$app/build/libs/$app.jar"
  if $reuse_jar && [[ ! -f "$jar" || ! -r "$jar" ]]; then
    printf '실행할 bootJar가 없습니다. %s\n' "$jar" >&2
    missing_tasks+=("$task")
  fi
done
if ((${#missing_tasks[@]})); then
  printf '먼저 빌드하세요. ./gradlew%s\n' "$(printf ' %s' "${missing_tasks[@]}")" >&2
  exit 1
fi
if $infra || $fakepg || $observability || $core || $payment || $settlement || $gateway || $realtime; then
  [[ -f .env ]] || { echo '.env가 필요합니다. cp .env.example .env 후 값을 설정하세요.' >&2; exit 1; }
  set -a
  source .env
  set +a
fi
if $web; then
  command -v pnpm >/dev/null || { echo 'pnpm이 필요합니다.' >&2; exit 1; }
  [[ -f web/.env.local ]] || { echo 'web/.env.local이 필요합니다. web/.env.example을 복사해 설정하세요.' >&2; exit 1; }
  [[ -d web/node_modules ]] || { echo 'web 의존성이 없습니다. cd web && pnpm install을 실행하세요.' >&2; exit 1; }
fi
if $core || $payment || $settlement || $realtime || $gateway; then
  [[ -n "${SCOUTER_AGENT_DIR:-}" ]] || { echo '개발 Java 앱은 Scouter를 사용합니다. .env 또는 환경 변수에 SCOUTER_AGENT_DIR을 지정하세요.' >&2; exit 1; }
  [[ -f "$SCOUTER_AGENT_DIR/scouter.agent.jar" ]] || { echo "SCOUTER_AGENT_DIR에 scouter.agent.jar가 없습니다: $SCOUTER_AGENT_DIR" >&2; exit 1; }
fi
pids=()
run_file=
stop_status=0
cleanup() {
  trap - EXIT
  trap '' INT TERM
  if ((${#pids[@]})); then
    for pid in "${pids[@]}"; do
      kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
    done
    wait || true
  fi
  if [[ -n "$run_file" ]]; then rm -f -- "$run_file" "$run_file.tmp"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if ! $reuse_jar && ((${#tasks[@]})); then ./gradlew "${tasks[@]}"; fi
if $infra; then
  docker compose up -d --wait postgres redis kafka object-storage
  docker compose run --rm --no-deps object-storage-init
  # 기존 볼륨에도 전용 DB를 준비한다
  ./scripts/db/ensure-payment-db.sh
fi
if $infra || $settlement; then ./scripts/db/ensure-settlement-db.sh; fi
if $observability; then docker compose -f compose.yaml -f compose.observability.yaml up -d --wait prometheus tempo grafana; fi
if ((${#tasks[@]})) || $web; then
  mkdir -p "$run_dir" build/dev/logs
  run_file="$run_dir/$$.pid"
  started=$(process_start_time "$$")
  # 자식 PID를 등록할 때까지 종료를 미뤄 정리 대상에서 빠지는 일이 없게 한다.
  trap 'stop_status=130' INT
  trap 'stop_status=143' TERM
  printf '%s\n' "$started" > "$run_file.tmp"
  mv -- "$run_file.tmp" "$run_file"
fi
start() {
  local name=$1
  shift
  ((stop_status == 0)) || exit "$stop_status"
  setsid "$@" > "build/dev/logs/$name.log" 2>&1 &
  pids+=("$!")
  printf '%s 실행 중 (로그: build/dev/logs/%s.log)\n' "$name" "$name"
}
start_java() {
  local name=$1
  local -a jvm_args=("-XX:+HeapDumpOnOutOfMemoryError" "-XX:HeapDumpPath=$ROOT/build/dev/logs/$name.hprof")
  if [[ -n "${SCOUTER_AGENT_DIR:-}" ]]; then
    local scouter_conf
    scouter_conf=$(scouter_config_file "$name")
    jvm_args+=("-javaagent:$SCOUTER_AGENT_DIR/scouter.agent.jar"
      "--add-opens=java.base/java.lang=ALL-UNNAMED"
      "--add-exports=java.base/sun.net=ALL-UNNAMED"
      "-Dscouter.config=$ROOT/deploy/scouter/$scouter_conf"
      "-Dnet_collector_ip=${SCOUTER_COLLECTOR_IP:-127.0.0.1}"
      "-Dobj_name=$name" -Djdk.attach.allowAttachSelf=true)
    printf 'Scouter agent 연결: obj_name=%s, collector=%s:6100\n' "$name" "${SCOUTER_COLLECTOR_IP:-127.0.0.1}" >&2
  fi
  start "$name" java "${jvm_args[@]}" -jar "$name/build/libs/$name.jar"
}
$fakepg && start fake-pg java -jar fake-pg/build/libs/fake-pg.jar
$core && start_java core
$payment && start_java payment
$settlement && start_java settlement
if $realtime && $slow_queue; then
  # QueueProperties(ticketing.realtime.queue)의 relaxed binding. .env보다 우선한다
  export TICKETING_REALTIME_QUEUE_ADMISSIONINTERVAL=5s TICKETING_REALTIME_QUEUE_ADMISSIONBATCHSIZE=1
  echo "대기열 입장 속도: 5초에 1명(--slow-queue)" >&2
fi
$realtime && start_java realtime
$gateway && start_java gateway
$web && start web bash -c 'cd "$1/web" && exec pnpm dev' _ "$ROOT"
if ((${#pids[@]})); then
  trap 'exit 130' INT
  trap 'exit 143' TERM
  ((stop_status == 0)) || exit "$stop_status"
  echo '앱 종료 방법은 Ctrl+C 또는 ./scripts/dev/run.sh --stop입니다.'
  set +e
  wait -n "${pids[@]}"
  status=$?
  set -e
  echo '앱 프로세스가 종료되어 나머지 앱을 정리합니다.' >&2
  ((status != 0)) || status=1
  exit "$status"
fi
