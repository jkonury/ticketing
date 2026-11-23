#!/usr/bin/env bash
# source 전용. 시나리오별 시드·데이터 검증·준비도 집계. run-once.sh 단계 함수가 호출하며 run_once의 local 변수를 동적 스코프로 읽고 쓴다.
# 필요한 전역: SCENARIO, OUT, ROOT, dir, schedule_file, COUPON_QUANTITY, COUPON_STRATEGY. 의존 함수: conflict_manifest(scenario-conflict.sh).

scenario_seed() {
  schedule_file="$OUT/schedule-$SCENARIO.json"
  if [[ $SCENARIO == coupon-drop ]]; then
    "$ROOT/scripts/loadtest/seed.sh" coupon --quantity "$COUPON_QUANTITY" >> "$dir/seed.log" 2>&1 \
      || { echo "쿠폰 시드 실패: $dir/seed.log" >&2; return 1; }
    schedule_file="$OUT/coupon.json"
  elif [[ $SCENARIO != smoke ]]; then
    "$ROOT/scripts/loadtest/seed.sh" schedule --scenario "$SCENARIO" >> "$dir/seed.log" 2>&1 \
      || { echo "회차 시드 실패: $dir/seed.log" >&2; return 1; }
  fi
}

scenario_verify_data() {
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

scenario_readiness() {
  # 시나리오 B: 1,000명 동시 경합 도달 여부. 미달·지각은 실패 근거로 남기며 합격 근거로 쓰지 않는다
  if [[ $SCENARIO == hot-seats && -f $dir/readiness.json ]]; then
    readiness=$(jq -r '"정시 준비 \(.readyOnTime // "기록 없음")/\(.vus), 지각 \(.readyLate // "기록 없음"), 예매 시도 \(.bookingAttempt // "기록 없음"), 동시 경합 \(if .fullContention then "충족" else "미충족(합격 근거 아님)" end)"' "$dir/readiness.json")
    printf 'b_readiness: %s\n' "$readiness" >> "$dir/measurement.txt"
  fi

  if [[ $SCENARIO == seat-conflict-refund && -f $dir/readiness.json ]]; then
    readiness=$(jq -r '"B 회원 정시 준비 \(.readyOnTime // "기록 없음")/\(.vus), 지각 \(.readyLate // "기록 없음"), 예매 시도 \(.bookingAttempt // "기록 없음")"' "$dir/readiness.json")
    printf 'conflict_readiness: %s\n' "$readiness" >> "$dir/measurement.txt"
  fi
}
