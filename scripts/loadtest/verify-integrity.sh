#!/usr/bin/env bash
# 사용: verify-integrity.sh <scheduleId>  (측정 뒤 실행. 위반이 있으면 exit 1)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
SCHEDULE_ID=${1:-}
[[ $SCHEDULE_ID =~ ^[0-9]+$ ]] || { echo '사용: verify-integrity.sh <scheduleId>' >&2; exit 1; }
for command in docker jq; do
  command -v "$command" >/dev/null || { printf '필요한 도구가 없습니다: %s\n' "$command" >&2; exit 1; }
done
mkdir -p build/loadtest

# booking 스키마만 조회한다. 스키마를 넘는 조인은 하지 않는다
psql_q() {
  docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qAt -c "$1"' sh "$1"
}

sold_dup=$(psql_q "SELECT count(*) FROM (SELECT 1 FROM booking.sold_seat WHERE schedule_id = $SCHEDULE_ID GROUP BY schedule_id, seat_id HAVING count(*) > 1) t")
confirmed_dup=$(psql_q "SELECT count(*) FROM (SELECT bs.seat_id FROM booking.booking_seat bs JOIN booking.booking b ON b.id = bs.booking_id WHERE b.schedule_id = $SCHEDULE_ID AND b.status = 'CONFIRMED' GROUP BY bs.seat_id HAVING count(DISTINCT b.id) > 1) t")
confirmed_seats=$(psql_q "SELECT count(*) FROM booking.booking_seat bs JOIN booking.booking b ON b.id = bs.booking_id WHERE b.schedule_id = $SCHEDULE_ID AND b.status = 'CONFIRMED'")
sold_seats=$(psql_q "SELECT count(*) FROM booking.sold_seat WHERE schedule_id = $SCHEDULE_ID")
status_counts=$(psql_q "SELECT coalesce(jsonb_object_agg(status, n), '{}'::jsonb) FROM (SELECT status, count(*) AS n FROM booking.booking WHERE schedule_id = $SCHEDULE_ID GROUP BY status) t")

db_sold=$(psql_q "SELECT seat_id FROM booking.sold_seat WHERE schedule_id = $SCHEDULE_ID" | sort)
redis_sold=$(docker compose exec -T redis redis-cli --raw HGETALL "booking:seats:{$SCHEDULE_ID}" | tr -d '\r' \
  | awk 'NR % 2 == 1 { field = $0 } NR % 2 == 0 && $0 == "SOLD" { print field }' | sort)
redis_sold_count=$(grep -c . <<<"$redis_sold" || true)
redis_diff=$(comm -3 <(printf '%s\n' "$db_sold") <(printf '%s\n' "$redis_sold") | grep -c . || true)

violations=0
report() { # <이름> <위반 여부 0|1> <설명>
  local mark=OK
  if (($2)); then mark=FAIL; violations=$((violations + 1)); fi
  printf '[%s] %s: %s\n' "$mark" "$1" "$3"
}
report 'sold_seat 중복' $((sold_dup != 0)) "중복 행 ${sold_dup}건"
report '확정 좌석 중복' $((confirmed_dup != 0)) "둘 이상의 CONFIRMED 예매에 속한 좌석 ${confirmed_dup}건"
report '판매 수 일치' $((confirmed_seats != sold_seats)) "CONFIRMED 예매 좌석 ${confirmed_seats} / sold_seat ${sold_seats}"
report 'Redis 일치' $((redis_diff != 0)) "Redis SOLD ${redis_sold_count} / DB sold_seat ${sold_seats}, 불일치 좌석 ${redis_diff}건"
printf '[INFO] 상태별 예매 수: %s, 판매 좌석 수: %s\n' "$status_counts" "$sold_seats"

jq -n --argjson scheduleId "$SCHEDULE_ID" --argjson soldDup "$sold_dup" --argjson confirmedDup "$confirmed_dup" \
  --argjson confirmedSeats "$confirmed_seats" --argjson soldSeats "$sold_seats" \
  --argjson redisSold "$redis_sold_count" --argjson redisDiff "$redis_diff" \
  --argjson statusCounts "$status_counts" --argjson violations "$violations" \
  '{scheduleId: $scheduleId, ok: ($violations == 0), violations: $violations,
    checks: {soldSeatDuplicates: $soldDup, confirmedSeatDuplicates: $confirmedDup,
             confirmedSeatCount: $confirmedSeats, soldSeatCount: $soldSeats,
             redisSoldCount: $redisSold, redisMismatch: $redisDiff},
    info: {bookingsByStatus: $statusCounts}}' > "build/loadtest/integrity-$SCHEDULE_ID.json"
((violations == 0)) || { printf '정합성 위반 %s건. build/loadtest/integrity-%s.json 참고\n' "$violations" "$SCHEDULE_ID" >&2; exit 1; }
