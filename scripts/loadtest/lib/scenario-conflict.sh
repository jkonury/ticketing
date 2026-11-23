#!/usr/bin/env bash
# source 전용. 필요한 전역: ROOT, OUT, TOOL, CONFLICT_PAIRS 등 seat-conflict-refund 실행 변수.
# 의존 함수: psql_q, redis_q (infra.sh).


# conflict_first_phase <dir> <schedule_file>: 첫 단계(A 회원 100명이 PENDING 예매)를 실행하고, 그 예매의 겹치는 좌석 선점 키만 지운다.
# 지우는 키는 부하 전용 회차의 키 중 값(소유자)이 방금 만든 예매의 hold_token인 것뿐이다. DB 제약·판매 행은 건드리지 않는다.
conflict_first_phase() {
  local dir=$1 schedule_file=$2 schedule_id members_min first_status=0 created
  schedule_id=$(jq -r .scheduleId "$schedule_file")
  members_min=$(jq -r .userIdMin "$OUT/members.json")
  if [[ $TOOL == gatling ]]; then
    # 첫 단계도 새 Gatling JVM이다. 로그·원시 결과는 first-gatling/ 아래에 두어 본 단계와 섞지 않는다
    mkdir -p "$dir/first-gatling"
    launch_gatling "$dir/first-gatling" "$schedule_file" PHASE=first
    wait "$GATLING_PID" || first_status=$?
    LOAD_PID=
    # Gatling은 assertion 미달을 종료 코드 2로 알린다. 예매 생성 수는 아래 PENDING 검사로 확인한다
    if ((first_status != 0 && first_status != 2)) || rg -q 'Exception in thread|UnsupportedClassVersionError' "$dir/first-gatling/gatling.log"; then
      echo "첫 단계 Gatling 오류(종료 코드 $first_status): $dir/first-gatling/gatling.log" >&2
      return 1
    fi
  else
    env PHASE=first BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET="$TICKETING_JWT_SECRET" MEMBERS_FILE="$OUT/members.json" SCHEDULE_FILE="$schedule_file" COUPON_FILE="$OUT/coupon.json" \
      READINESS_FILE="$dir/conflict-first-readiness.json" taskset -c "$K6_CPUS" "$OUT/k6" run --summary-export "$dir/k6-first-summary.json" \
      loadtest/scenarios/seat-conflict-refund.ts > "$dir/k6-first.log" 2>&1 || first_status=$?
    if ((first_status != 0 && first_status != 99)) || rg -q 'source=stacktrace|script exception' "$dir/k6-first.log"; then
      echo "첫 단계 k6 오류(종료 코드 $first_status): $dir/k6-first.log" >&2
      return 1
    fi
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
