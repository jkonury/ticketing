#!/usr/bin/env bash
# source 전용. run_once는 run_prepare → run_load → run_collect → run_verify → run_summarize 순서로 호출한다.
# 단계 함수는 run_once의 local 변수를 동적 스코프로 읽고 쓴다. 공유 변수는 run_once 맨 위에서만 local 선언하고 단계 함수에서는 대입만 한다.
# 필요한 전역: 옵션 변수, OUT, RUN_ROOT, SUMMARY 등. 의존 함수: infra.sh·gatling.sh·metrics.sh·warmup.sh·scenario-conflict.sh.

run_prepare() {
  mkdir -p "$dir"
  write_env "$dir"
  publish_dashboard "$dir" starting
  local failure=0 duplicate=0
  if $FAILURE_INJECT; then failure=0.1; duplicate=0.3; fi
  # 보상 검증은 결제 실패와 좌석 충돌 환불을 구분하려고 실패율 0, 웹훅 중복 30%로 고정한다
  if [[ $SCENARIO == seat-conflict-refund ]]; then failure=0; duplicate=0.3; fi

  start_apps "$dir" "$failure" "$duplicate" || return 1

  "$ROOT/scripts/loadtest/seed.sh" members > "$dir/seed.log" 2>&1 || { echo "회원 시드 실패: $dir/seed.log" >&2; return 1; }
  schedule_file="$OUT/schedule-$SCENARIO.json"
  if [[ $SCENARIO == coupon-drop ]]; then
    "$ROOT/scripts/loadtest/seed.sh" coupon --quantity "$COUPON_QUANTITY" >> "$dir/seed.log" 2>&1 \
      || { echo "쿠폰 시드 실패: $dir/seed.log" >&2; return 1; }
    schedule_file="$OUT/coupon.json"
  elif [[ $SCENARIO != smoke ]]; then
    "$ROOT/scripts/loadtest/seed.sh" schedule --scenario "$SCENARIO" >> "$dir/seed.log" 2>&1 \
      || { echo "회차 시드 실패: $dir/seed.log" >&2; return 1; }
  fi

  local warm_ok=true
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
      if [[ $TOOL == gatling ]]; then
        publish_dashboard "$dir" warmup --round "$round"
        launch_gatling "$dir/warmup-$round" "$schedule_file"
        wait "$GATLING_PID" || warm_status=$?
        LOAD_PID=
        enrich_gatling "$dir/warmup-$round" || warm_ok=false
      else
        env BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$schedule_file" \
          READINESS_FILE="$dir/warmup-readiness-$round.json" \
          "${warm_vus[@]}" taskset -c "$K6_CPUS" "$OUT/k6" run --summary-export "$dir/warmup-k6-summary-$round.json" "loadtest/scenarios/$SCENARIO.ts" > "$dir/warmup-k6-$round.log" 2>&1 || warm_status=$?
      fi
      if [[ $SCENARIO != smoke ]]; then
        warm_ids="${warm_ids:+$warm_ids,}$warm_schedule"
        if ! warmup_settle "$dir" "$round" "$warm_schedule" "$warm_status"; then
          warm_ok=false
          echo "워밍업 $round회 비동기 처리가 상한 안에 끝나지 않았습니다: $dir/warmup-settle-$round.json" >&2
        fi
        if [[ $TOOL == gatling ]]; then
          "$ROOT/scripts/loadtest/verify-integrity.sh" "$warm_schedule" > "$dir/warmup-$round/integrity.log" 2>&1 || warm_ok=false
          cp "$OUT/integrity-$warm_schedule.json" "$dir/warmup-$round/integrity.json" || warm_ok=false
          python3 "$ROOT/scripts/loadtest/gatling-results.py" judge "$dir/warmup-$round" --scenario "$SCENARIO" \
            --limit "$(gatling_limit)" --exit-code "$warm_status" --warmup > "$dir/warmup-$round/verdict.log" 2>&1 || warm_ok=false
          publish_dashboard "$dir" warmup --round "$round" --artifacts "$dir/warmup-$round"
          $warm_ok || { echo "워밍업 $round회 무효: $dir/warmup-$round/verdict.json" >&2; return 1; }
        fi
        "$ROOT/scripts/loadtest/seed.sh" schedule --scenario "$SCENARIO" >> "$dir/seed.log" 2>&1 \
          || { echo "워밍업 $round회 뒤 회차 시드 실패: $dir/seed.log" >&2; return 1; }
      elif ((warm_status != 0)); then
        warm_ok=false
        echo "smoke 워밍업 $round회 실패(종료 코드 $warm_status): $dir/warmup-k6-$round.log" >&2
      fi
    done
    $warm_ok || return 1
    [[ -z $RESTART_AFTER ]] || restart_after_warmup "$dir" || return 1
  fi

  scenario_env=()
  if [[ $SCENARIO == seat-conflict-refund ]]; then
    conflict_first_phase "$dir" "$schedule_file" || return 1
    scenario_env=(PHASE=second FIRST_FILE="$dir/conflict-first.json")
  fi
  k6_args=(run --summary-export "$dir/k6-summary.json")
  if $OBSERVABILITY; then k6_args+=(-o experimental-prometheus-rw); fi
}

run_load() {
  local vus_env=()
  [[ -z $VUS ]] || vus_env=(VUS="$VUS")
  # 부하 도구별 로그·요약 파일. gatling은 k6 요약과 같은 형태의 JSON을 SUMMARY_FILE로 남긴다
  load_log="$dir/$TOOL.log" load_summary="$dir/$TOOL-summary.json"
  # 본 부하 직전 직접 수집(서버 지표 사전값). 워밍업이 있으면 워밍업과 시드 요청은 여기까지의 값에 들어간다
  collect_metrics "$dir/server-metrics-pre.json" || { echo "본 측정 전 지표 수집 실패: $dir/server-metrics-pre.json" >&2; return 1; }
  publish_dashboard "$dir" measurement --round "$($WARMUP && echo "$WARMUP_ROUNDS" || echo 0)"
  # 호스트 TCP 큐·재전송 진단(tcp-delta.json). 진단 전용이라 실패해도 실행과 판정에 영향을 주지 않는다
  python3 "$ROOT/scripts/loadtest/tcp-probe.py" snapshot "$dir/tcp-pre.json" 2>"$dir/tcp-probe.log" || true
  {
    printf 'tcp-sysctl: %s\n' "$(jq -c .sysctl "$dir/tcp-pre.json" 2>/dev/null || echo n/a)"
    printf 'tcp-listen-queues(ss -ltn 상당, 부하 직전): %s\n' "$(jq -c .listen "$dir/tcp-pre.json" 2>/dev/null || echo n/a)"
  } >> "$dir/env.txt"
  k6_from=$(date +%s)
  printf 'k6_start_unix_seconds: %s\n' "$k6_from" > "$dir/measurement.txt"
  local codelist_pid=
  $CODELIST && dump_codelist "$dir" pre
  if [[ $TOOL == gatling ]]; then
    launch_gatling "$dir" "$schedule_file" "${scenario_env[@]}"
  else
    env K6_PROMETHEUS_RW_SERVER_URL=http://localhost:19090/api/v1/write K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99),avg,max' \
      BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$schedule_file" \
      READINESS_FILE="$dir/readiness.json" "${vus_env[@]}" "${scenario_env[@]}" taskset -c "$K6_CPUS" "$OUT/k6" "${k6_args[@]}" "loadtest/scenarios/$SCENARIO.ts" > "$load_log" 2>&1 &
  fi
  local k6_pid=$!
  [[ $TOOL != gatling ]] || k6_pid=$GATLING_PID
  if $CODELIST; then
    (sleep "$CODELIST_DELAY"; dump_codelist "$dir" burst) &
    codelist_pid=$!
  fi
  python3 "$ROOT/scripts/loadtest/sample-resources.py" "$k6_pid" "$dir/resources.csv" > "$dir/resource-sampler.log" 2>&1 &
  SAMPLER_PID=$!
  python3 "$ROOT/scripts/loadtest/tcp-probe.py" sample "$k6_pid" "$dir/tcp-samples.csv" 0.15 >> "$dir/tcp-probe.log" 2>&1 &
  TCP_SAMPLER_PID=$!
  "$ROOT/scripts/loadtest/sample-pg.sh" "$k6_pid" "$dir/pg-activity.log" &
  PG_SAMPLER_PID=$!
  LOAD_PID=$k6_pid
  wait "$k6_pid" || k6_status=$?
  if [[ -n $codelist_pid ]]; then kill "$codelist_pid" 2>/dev/null || true; wait "$codelist_pid" 2>/dev/null || true; fi
  LOAD_PID=
  python3 "$ROOT/scripts/loadtest/tcp-probe.py" snapshot "$dir/tcp-post.json" >> "$dir/tcp-probe.log" 2>&1 || true
  if [[ $TOOL == gatling ]]; then
    enrich_gatling "$dir" || { server_delta_ok=false; echo "Gatling 원시 결과 집계 실패: $dir/gatling-results.log" >&2; }
  fi
  k6_to=$(date +%s)
  printf 'k6_end_unix_seconds: %s\nk6_exit_code: %s\n' "$k6_to" "$k6_status" >> "$dir/measurement.txt"
  # 본 부하 직후 직접 수집(서버 지표 사후값)
  collect_metrics "$dir/server-metrics-post.json" || { server_delta_ok=false; echo "본 측정 후 지표 수집 실패: $dir/server-metrics-post.json" >&2; }
  wait "$SAMPLER_PID" || true
  wait "$TCP_SAMPLER_PID" || true
  TCP_SAMPLER_PID=
  wait "$PG_SAMPLER_PID" || true
  SAMPLER_PID=
}

run_collect() {
  # 서버 지표 차분: 본 부하 직전·직후 직접 수집값의 차이만 쓴다(워밍업·시드 요청 제외)
  local delta_status=0
  if $server_delta_ok; then
    python3 "$ROOT/scripts/loadtest/metrics-delta.py" diff "$dir/server-metrics-pre.json" "$dir/server-metrics-post.json" "$dir/server-delta.json" \
      --scenario "$SCENARIO" --k6-summary "$load_summary" --label measurement > "$dir/server-delta.log" 2>&1 || delta_status=$?
    ((delta_status != 2)) || { server_delta_ok=false; echo "서버 지표 차분 오류(reset·누락): $dir/server-delta.log" >&2; }
    if ((delta_status == 1)); then echo "통제 입력 불일치: $dir/server-delta.log" >&2; fi
  fi
  python3 "$ROOT/scripts/loadtest/tcp-probe.py" summarize "$dir" >> "$dir/tcp-probe.log" 2>&1 || true
  printf 'server_delta_valid: %s\nserver_delta_controlled_inputs: %s\n' "$($server_delta_ok && echo yes || echo no)" "$( ((delta_status == 1)) && echo 불일치 || echo 일치·판정불가)" >> "$dir/measurement.txt"
  if $WARMUP; then
    # 워밍업 구간(워밍업 직전~본 부하 직전) 차분은 워밍업 요청이 이 구간에만 들어갔음을 확인하는 통제 입력이다
    local warm_summaries=("$dir"/warmup-k6-summary-*.json)
    [[ $TOOL != gatling ]] || warm_summaries=("$dir"/warmup-*/gatling-summary.json)
    if [[ $SCENARIO != smoke ]]; then
      jq -s '{metrics: {flow_booking_attempt: {count: (map(.metrics.flow_booking_attempt.count // 0) | add)}, http_reqs: {count: (map(.metrics.http_reqs.count // 0) | add)}}}' \
        "${warm_summaries[@]}" > "$dir/warmup-k6-summary-total.json" 2>/dev/null || true
    else
      jq -s '{metrics: {http_reqs: {count: (map(.metrics.http_reqs.count // 0) | add)}}}' "${warm_summaries[@]}" > "$dir/warmup-k6-summary-total.json" 2>/dev/null || true
    fi
    [[ -n $RESTART_AFTER ]] || python3 "$ROOT/scripts/loadtest/metrics-delta.py" diff "$dir/server-metrics-pre-warmup.json" "$dir/server-metrics-pre.json" "$dir/server-delta-warmup.json" \
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
  if [[ $SCENARIO == coupon-drop ]]; then
    sleep 5
    local definition_id
    definition_id=$(jq -r .definitionId "$schedule_file")
    if "$ROOT/scripts/loadtest/verify-coupon-integrity.sh" "$definition_id" "$COUPON_STRATEGY" > "$dir/integrity.log" 2>&1; then
      integrity='통과'
    else
      integrity='위반(integrity.log 참고)'
      integrity_ok=false
    fi
    cp "$OUT/coupon-integrity-$definition_id.json" "$dir/integrity.json" 2>/dev/null || true
  elif [[ $SCENARIO != smoke ]]; then
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

}

run_verify() {
  if $WARMUP && [[ $SCENARIO != smoke ]]; then
    if check_isolation "$dir" "$(jq -r .scheduleId "$schedule_file")" "$warm_ids"; then isolation='격리 확인'; else isolation='격리 위반(isolation.json 참고)'; isolation_ok=false; fi
  fi
  # 시나리오 B: 1,000명 동시 경합 도달 여부. 미달·지각은 실패 근거로 남기며 합격 근거로 쓰지 않는다
  if [[ $SCENARIO == hot-seats && -f $dir/readiness.json ]]; then
    readiness=$(jq -r '"정시 준비 \(.readyOnTime // "기록 없음")/\(.vus), 지각 \(.readyLate // "기록 없음"), 예매 시도 \(.bookingAttempt // "기록 없음"), 동시 경합 \(if .fullContention then "충족" else "미충족(합격 근거 아님)" end)"' "$dir/readiness.json")
    printf 'b_readiness: %s\n' "$readiness" >> "$dir/measurement.txt"
  fi

  if [[ $SCENARIO == seat-conflict-refund && -f $dir/readiness.json ]]; then
    readiness=$(jq -r '"B 회원 정시 준비 \(.readyOnTime // "기록 없음")/\(.vus), 지각 \(.readyLate // "기록 없음"), 예매 시도 \(.bookingAttempt // "기록 없음")"' "$dir/readiness.json")
    printf 'conflict_readiness: %s\n' "$readiness" >> "$dir/measurement.txt"
  fi

  if [[ $TOOL == gatling ]]; then
    local verdict_args=(--scenario "$SCENARIO" --limit "$(gatling_limit)" --exit-code "$k6_status" --server)
    $WARMUP && verdict_args+=(--isolation)
    [[ -z $RESTART_AFTER ]] || verdict_args+=(--no-warmup-delta)
    python3 "$ROOT/scripts/loadtest/gatling-results.py" judge "$dir" "${verdict_args[@]}" > "$dir/verdict.log" 2>&1 || verdict_ok=false
    publish_dashboard "$dir" completed --round "$($WARMUP && echo "$WARMUP_ROUNDS" || echo 0)"
    $verdict_ok || echo "Gatling 최종 판정 미달: $dir/verdict.json" >&2
  fi

  cleanup # SIGTERM으로 종료해 JFR이 파일로 남게 한다

  # k6 종료 코드 99, gatling 종료 코드 2는 threshold(assertion) 미달이다. 기준 측정은 미달 항목을 그대로 기록하므로 스크립트 실패로 보지 않는다.
  # smoke의 99는 checks 실패(연결 이상)라서 실패로 본다
  local threshold_code=99
  [[ $TOOL != gatling ]] || threshold_code=2
  if ((k6_status == threshold_code)) && [[ $SCENARIO != smoke ]]; then
    thresholds="미달($TOOL 종료 코드 $threshold_code)"
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
    local p95_metric='http_req_duration{name:booking_create}'
    [[ $SCENARIO != coupon-drop ]] || p95_metric=claim_latency
    p95=$(jq -r --arg metric "$p95_metric" '.metrics[$metric]["p(95)"] // "n/a"' "$load_summary" 2>/dev/null || echo n/a)
  fi
  if $WARMUP && ! $server_delta_ok; then script_ok=false; fi
}

run_summarize() {
  local tool_rss_mb host_mem_peak
  # 앱·인프라·부하 도구가 한 호스트에서 돌므로 호스트 전체 메모리 사용률 최대값도 남긴다(총량 대비 MemAvailable 최소값)
  host_mem_peak=$(awk -F, 'NR > 1 && $5 + 0 > 0 {used = 1 - ($6 + 0) / ($5 + 0); if (used > max) max = used} END {if (max > 0) printf "%.1f%%", max * 100; else print "n/a"}' "$dir/resources.csv" 2>/dev/null || echo n/a)
  # 부하 도구 프로세스의 최대 RSS(MB). k6와 gatling을 같은 방식으로 비교하는 지표다
  tool_rss_mb=$(awk -F, 'NR > 1 && $3 + 0 > max {max = $3 + 0} END {if (max > 0) printf "%.0f", max / 1048576; else print "n/a"}' "$dir/resources.csv" 2>/dev/null || echo n/a)
  SUMMARY+=("run $n: 도구 $TOOL, thresholds $thresholds, 부하 도구 최대 RSS ${tool_rss_mb}MB, 호스트 메모리 최대 사용률 ${host_mem_peak}, 요청 $reqs, http_req_failed $failed, 예매 생성 p95 $p95, 정합성 $integrity, 워밍업 $($WARMUP && echo "$WARMUP_ROUNDS회, $isolation" || echo 없음), 서버 차분 $($server_delta_ok && echo 유효 || echo 오류), B 준비 $readiness, 보상 $compensation, 디렉터리 $dir")
  $script_ok && $integrity_ok && $isolation_ok && $compensation_ok && $verdict_ok
}

run_once() {
  local n=$1 dir="$RUN_ROOT/$1"
  local schedule_file= warm_ids= server_delta_ok=true
  local scenario_env=() k6_args=() k6_status=0
  local load_log= load_summary= k6_from k6_to
  local compensation='해당 없음' compensation_ok=true
  local integrity='해당 없음' integrity_ok=true
  local isolation='해당 없음' isolation_ok=true
  local readiness='해당 없음' verdict_ok=true
  local thresholds='통과' script_ok=true reqs failed p95
  run_prepare || return 1
  run_load || return 1
  run_collect || return 1
  run_verify || return 1
  run_summarize
}
