#!/usr/bin/env bash
# source 전용. 필요한 전역: ROOT, BOOKING_P95_MS, VUS 등 Gatling 실행 변수(launch_gatling 본문 참고).
# 의존 함수: run.sh에 정의된 함수(호출 시점에 해석된다).

# 회차마다 새 Gatling JVM. 원시 로그와 준비·응답 분류 요약은 디렉터리로 격리한다.
launch_gatling() {
  local dir=$1 schedule_file=$2
  shift 2
  local simulation=QueueSurgeSimulation report_args=() load_env=()
  case "$SCENARIO" in
    hot-seats) simulation=HotSeatsSimulation ;;
    seat-conflict-refund) simulation=SeatConflictRefundSimulation ;;
    coupon-drop) simulation=CouponDropSimulation ;;
  esac
  $GATLING_REPORT || report_args=(-nr)
  [[ -z $VUS ]] || load_env+=(VUS="$VUS")
  [[ -z $BOOKING_P95_MS ]] || load_env+=(BOOKING_P95_MS="$BOOKING_P95_MS")
  mkdir -p "$dir/gatling-prefs/.java/.userPrefs"
  cp "$schedule_file" "$dir/schedule.json"
  env BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$dir/schedule.json" COUPON_FILE="$OUT/coupon.json" \
    READINESS_FILE="$dir/readiness.json" SUMMARY_FILE="$dir/gatling-summary.json" "${load_env[@]}" "$@" \
    taskset -c "$K6_CPUS" java -Xmx"$GATLING_HEAP" "-Xlog:gc*:file=$dir/gatling-gc.log" -XX:+HeapDumpOnOutOfMemoryError "-XX:HeapDumpPath=$dir/gatling.hprof" \
    --enable-native-access=ALL-UNNAMED "-Djava.util.prefs.userRoot=$dir/gatling-prefs" \
    --add-opens java.base/java.lang=ALL-UNNAMED -cp "$GATLING_DIST/lib/*" io.gatling.app.Gatling \
    -s "io.github.ticketing.loadtest.$simulation" -rf "$dir/gatling-results" "${report_args[@]}" > "$dir/gatling.log" 2>&1 &
  GATLING_PID=$!
  LOAD_PID=$GATLING_PID
}

enrich_gatling() {
  python3 "$ROOT/scripts/loadtest/gatling-results.py" enrich "$1" > "$1/gatling-results.log" 2>&1
}

gatling_limit() {
  if [[ -n $BOOKING_P95_MS ]]; then echo "$BOOKING_P95_MS"
  elif [[ -n $VUS && $VUS -le 100 ]]; then echo 300
  else echo 1500; fi
}
