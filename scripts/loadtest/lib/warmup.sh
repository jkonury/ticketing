#!/usr/bin/env bash
# source 전용. 필요한 전역: ROOT, OUT, SCENARIO, WARMUP_SETTLE_TIMEOUT, WARMUP_QUEUE_TIMEOUT.
# 의존 함수: psql_q, redis_q (infra.sh).

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
