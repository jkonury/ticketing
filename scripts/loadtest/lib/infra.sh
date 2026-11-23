#!/usr/bin/env bash
# source 전용. 필요한 전역: ROOT, OUT, RUN_ROOT, PIDS, compose, 앱 기동 옵션 변수들(run.sh 상단 선언).
# 의존 함수: 서로 간 및 run.sh의 dump_codelist 등(호출 시점에 해석된다).

cleanup() {
  if [[ -n $LOAD_PID ]]; then
    kill "$LOAD_PID" 2>/dev/null || true
    wait "$LOAD_PID" 2>/dev/null || true
    LOAD_PID=
  fi
  if [[ -n $SAMPLER_PID ]]; then
    kill "$SAMPLER_PID" 2>/dev/null || true
    wait "$SAMPLER_PID" 2>/dev/null || true
    SAMPLER_PID=
  fi
  if [[ -n $TCP_SAMPLER_PID ]]; then
    kill "$TCP_SAMPLER_PID" 2>/dev/null || true
    wait "$TCP_SAMPLER_PID" 2>/dev/null || true
    TCP_SAMPLER_PID=
  fi
  if ((${#PIDS[@]})); then
    kill "${PIDS[@]}" 2>/dev/null || true
    wait "${PIDS[@]}" 2>/dev/null || true
    PIDS=()
  fi
}

# start <name> <dir> <heap> <jar> [환경변수...]  (환경변수는 NAME=VALUE 형태)
start() {
  local name=$1 dir=$2 heap=$3 jar=$4
  shift 4
  local opts=($heap) # 힙 옵션은 공백으로 나뉜 한 문자열
  opts+=("-XX:+HeapDumpOnOutOfMemoryError" "-XX:HeapDumpPath=$dir/$name.hprof")
  if $JFR; then
    opts+=("-XX:StartFlightRecording=filename=$dir/$name.jfr,dumponexit=true,settings=profile,jdk.VirtualThreadPinned#threshold=0ms")
  fi
  if [[ -n $JVM_OPTS && ( $name == core || $name == gateway ) ]]; then
    read -r -a extra_opts <<< "$JVM_OPTS"
    opts+=("${extra_opts[@]}")
  fi
  # payment는 전용 Scouter 설정 파일이 없어 에이전트를 붙이지 않는다
  if [[ -n "${SCOUTER_AGENT_DIR:-}" && $name != payment ]]; then
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
  local name=$1 port=$2 pid=$3 dir=$4 deadline=$((SECONDS + 180))
  local health_path=/actuator/health
  [[ $name != core && $name != payment && $name != gateway ]] || health_path=/actuator/health/readiness
  until curl -fsS "http://localhost:$port$health_path" 2>/dev/null | rg -q '"status"\s*:\s*"UP"'; do
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
    printf 'booking-p95-ms: %s\n' "${BOOKING_P95_MS:-VU별 기본}"
    printf 'internal-warmup: %s\n' "$INTERNAL_WARMUP"
    printf 'jvm-opts(core·gateway): %s\n' "${JVM_OPTS:-없음}"
    printf 'generator-warmup: 매회 새 JVM, 외부 warmup 비활성\n'
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
      for name in core gateway realtime payment; do printf 'aot-cache-size-%s: %s\n' "$name" "$(stat -c %s "$AOT_DIR/$name.aot")"; done
    else
      printf 'aot-cache: 미사용\n'
    fi
    printf 'heap: core 2g, payment 1g, realtime 1g, gateway 1g, fake-pg 512m\n'
    if [[ -n ${SCOUTER_AGENT_DIR:-} ]]; then
      sha256sum "$SCOUTER_AGENT_DIR/scouter.agent.jar"
      for name in core gateway realtime fake-pg; do
        sha256sum "$ROOT/deploy/scouter/$(scouter_config_file "$name")"
      done
    fi
    LC_ALL=C lscpu -e=CPU,CORE,SOCKET
  } > "$1/env.txt"
}

start_apps() {
  local dir=$1 failure=$2 duplicate=$3 batch=() obs=()
  [[ $SCENARIO != hot-seats && $SCENARIO != seat-conflict-refund ]] || batch=(TICKETING_REALTIME_QUEUE_ADMISSION_BATCH_SIZE=1000)
  if $OBSERVABILITY; then obs=(TRACING_EXPORT_ENABLED=true TRACING_SAMPLING_PROBABILITY=0.1); fi
  start fake-pg "$dir" "-Xms512m -Xmx512m" fake-pg/build/libs/fake-pg.jar \
    FAKE_PG_WEBHOOK_URL=http://localhost:18085/api/v1/payments/webhooks/fake-pg \
    FAKE_PG_BEHAVIOR_FAILURE_RATE="$failure" FAKE_PG_BEHAVIOR_DUPLICATE_RATE="$duplicate" "${obs[@]}"
  start core "$dir" "-Xms2g -Xmx2g" core/build/libs/core.jar STARTUP_WARMUP_ENABLED="$INTERNAL_WARMUP" COUPON_LIMITED_CLAIM_STRATEGY="$COUPON_STRATEGY" "${obs[@]}"
  start realtime "$dir" "-Xms1g -Xmx1g" realtime/build/libs/realtime.jar "${batch[@]}" "${obs[@]}"
  local gw_env=()
  if $NO_RATE_LIMIT; then gw_env=(TICKETING_GATEWAY_RATE_LIMIT_ENABLED=false); fi
  start gateway "$dir" "-Xms1g -Xmx1g" gateway/build/libs/gateway.jar STARTUP_WARMUP_ENABLED="$INTERNAL_WARMUP" "${gw_env[@]}" "${obs[@]}"
  # payment는 PIDS 뒤쪽(인덱스 4)에 둔다. 기존 인덱스(fake-pg 0, core 1, realtime 2, gateway 3)를 유지한다
  start payment "$dir" "-Xms1g -Xmx1g" payment/build/libs/payment.jar STARTUP_WARMUP_ENABLED="$INTERNAL_WARMUP" "${obs[@]}"
  health fake-pg 18090 "${PIDS[0]}" "$dir" || return 1
  health core 18080 "${PIDS[1]}" "$dir" || return 1
  health realtime 18081 "${PIDS[2]}" "$dir" || return 1
  health gateway 18000 "${PIDS[3]}" "$dir" || return 1
  health payment 18085 "${PIDS[4]}" "$dir" || return 1
  if $INTERNAL_WARMUP; then
    printf '\n내부 예열 결과(기동 로그, timeout 시 취소 시점 호출 수):\n' >> "$dir/env.txt"
    rg 'startup-warmup outcome=' "$dir/core.log" "$dir/gateway.log" >> "$dir/env.txt"
    rg 'payment-warmup outcome=' "$dir/payment.log" >> "$dir/env.txt" # payment는 로그 접두어가 payment-warmup이다
    if ! rg -q 'startup-warmup outcome=completed ' "$dir/core.log" ||
      ! rg -q 'startup-warmup outcome=completed ' "$dir/gateway.log" ||
      ! rg -q 'payment-warmup outcome=completed ' "$dir/payment.log"; then
      echo "내부 예열 미완료로 본 측정을 시작하지 않습니다: $dir/env.txt" >&2
      return 1
    fi
  fi
}

# restart_after_warmup <디렉터리>: 외부 예열 직후 지정 서비스만 새 JVM으로 재기동한다(진단 전용). core → gateway 순서로 띄워 Gateway 예열이 core ready 뒤에 돈다
restart_after_warmup() {
  local dir=$1 name idx port obs=() batch=() gw_env=()
  if $OBSERVABILITY; then obs=(TRACING_EXPORT_ENABLED=true TRACING_SAMPLING_PROBABILITY=0.1); fi
  [[ $SCENARIO != hot-seats ]] || batch=(TICKETING_REALTIME_QUEUE_ADMISSION_BATCH_SIZE=1000)
  if $NO_RATE_LIMIT; then gw_env=(TICKETING_GATEWAY_RATE_LIMIT_ENABLED=false); fi
  for name in realtime core gateway; do
    [[ ,$RESTART_AFTER, == *,$name,* ]] || continue
    case $name in core) idx=1 ;; realtime) idx=2 ;; gateway) idx=3 ;; esac
    kill "${PIDS[$idx]}" 2>/dev/null || true
    wait "${PIDS[$idx]}" 2>/dev/null || true
    mv "$dir/$name.log" "$dir/$name-prewarm.log"
    case $name in
      core) start core "$dir" "-Xms2g -Xmx2g" core/build/libs/core.jar STARTUP_WARMUP_ENABLED=true "${obs[@]}"; port=18080 ;;
      realtime) start realtime "$dir" "-Xms1g -Xmx1g" realtime/build/libs/realtime.jar "${batch[@]}" "${obs[@]}"; port=18081 ;;
      gateway) start gateway "$dir" "-Xms1g -Xmx1g" gateway/build/libs/gateway.jar STARTUP_WARMUP_ENABLED=true "${gw_env[@]}" "${obs[@]}"; port=18000 ;;
    esac
    local new=${PIDS[${#PIDS[@]}-1]}
    unset "PIDS[${#PIDS[@]}-1]"
    PIDS[$idx]=$new
    health "$name" "$port" "$new" "$dir" || return 1
    if [[ $name != realtime ]] && ! rg -q 'startup-warmup outcome=completed ' "$dir/$name.log"; then
      echo "재기동한 $name 내부 예열 미완료: $dir/$name.log" >&2
      return 1
    fi
  done
  printf 'restart-after-warmup: %s (재기동 서비스 이전 로그: <서비스>-prewarm.log)\n' "$RESTART_AFTER" >> "$dir/env.txt"
}

psql_q() { # booking 스키마 조회용. 스키마를 넘는 조인은 하지 않는다
  docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qAt -c "$1"' sh "$1"
}
redis_q() { docker compose exec -T redis redis-cli --raw "$@" | tr -d '\r'; }
