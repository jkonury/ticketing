#!/usr/bin/env bash
# source 전용. 필요한 전역: AOT_DIR, AOT_PHASE, OUT, SCENARIO, VUS, K6_CPUS, AOT_TRAIN_ROUNDS, ROOT.
# 의존 함수: start_apps, cleanup (run.sh에 정의, 호출 시점에 해석된다).

# 중첩 jar는 AOT 캐시 대상이 안 되므로 jar를 풀고, 학습 기동에서 캐시를 만든 뒤 측정 기동에서 쓴다
prepare_aot() {
  mkdir -p "$AOT_DIR"
  local name
  for name in core gateway realtime payment; do
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
  for name in core gateway realtime payment; do
    [[ -s $AOT_DIR/$name.aot ]] || { echo "AOT 캐시가 만들어지지 않았습니다: $AOT_DIR/$name.aot (로그: $dir/$name.log)" >&2; return 1; }
  done
  AOT_PHASE=use
}
