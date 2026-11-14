#!/usr/bin/env bash
# 사용: verify-compensation.sh <scheduleId> <manifest.json> <출력 디렉터리> [fake-pg.log 경로]
# seat-conflict-refund 시나리오의 기대 결과(그룹마다 확정 1·SEAT_ALREADY_SOLD 취소 1·패자 sold_seat 0·패자 REFUNDED)를 검증한다.
# booking·payment는 각 스키마에서 따로 조회해 주문 ID로 jq에서 대조하고, PG는 거래 조회 API로 확인한다. 스키마를 넘는 조인은 하지 않는다.
# 비동기 환불은 COMPENSATION_TIMEOUT초(기본 120)까지 기다린다. 미완료는 실패로 남기며 원장·Redis 상태는 항상 출력 디렉터리에 보존한다.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
SCHEDULE_ID=${1:-}
MANIFEST=${2:-}
OUTDIR=${3:-}
PG_LOG=${4:-}
TIMEOUT=${COMPENSATION_TIMEOUT:-120}
PG_URL=${FAKE_PG_URL:-http://localhost:18090}
[[ $SCHEDULE_ID =~ ^[0-9]+$ && -f $MANIFEST && -n $OUTDIR ]] || { echo '사용: verify-compensation.sh <scheduleId> <manifest.json> <출력 디렉터리> [fake-pg.log]' >&2; exit 1; }
for command in docker jq curl; do
  command -v "$command" >/dev/null || { printf '필요한 도구가 없습니다: %s\n' "$command" >&2; exit 1; }
done
mkdir -p "$OUTDIR"

psql_q() {
  docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qAt -c "$1"' sh "$1"
}

ORDER_IDS=$(jq -r '[.pairs[] | .bookingA, .bookingB] | join(",")' "$MANIFEST")
PAIRS=$(jq -r '.pairs | length' "$MANIFEST")

dump_ledgers() {
  # booking 스키마 원장
  psql_q "SELECT coalesce(jsonb_agg(r), '[]'::jsonb) FROM (
    SELECT b.id, b.member_id AS \"memberId\", b.status, b.cancel_reason AS \"cancelReason\", b.total_amount::bigint AS amount,
      (SELECT count(*) FROM booking.sold_seat s WHERE s.booking_id = b.id) AS sold,
      (SELECT jsonb_agg(bs.seat_id ORDER BY bs.seat_id) FROM booking.booking_seat bs WHERE bs.booking_id = b.id) AS seats
    FROM booking.booking b WHERE b.schedule_id = $SCHEDULE_ID) r" > "$OUTDIR/ledger-booking.json"
  # payment 스키마 원장(주문 ID는 예매 ID)
  psql_q "SELECT coalesce(jsonb_agg(r), '[]'::jsonb) FROM (
    SELECT p.order_id AS \"orderId\", p.status, p.amount::bigint AS amount, p.pg_transaction_id AS \"txnId\",
      p.refund_requested_at IS NOT NULL AS \"refundRequested\", p.refunded_at IS NOT NULL AS refunded,
      (SELECT count(*) FROM payment.webhook_event w WHERE w.transaction_id = p.pg_transaction_id) AS \"webhookEvents\"
    FROM payment.payment p WHERE p.order_id IN ($ORDER_IDS)) r" > "$OUTDIR/ledger-payment.json"
}

pending_count() {
  jq -n --slurpfile b "$OUTDIR/ledger-booking.json" --slurpfile p "$OUTDIR/ledger-payment.json" --argjson expected "$((PAIRS * 2))" '
    ([$b[0][] | select(.status == "PENDING")] | length) as $pending
    | ([$p[0][] | select(.status == "REQUESTED" or .status == "REFUND_REQUESTED")] | length) as $inflight
    | ([$p[0][]] | length) as $payments
    | ([$b[0][] | select(.status == "CANCELLED")] | length) as $cancelled
    | ([$p[0][] | select(.status == "REFUNDED")] | length) as $refunded
    | if $pending + $inflight > 0 or $payments < $expected or $refunded < $cancelled then 1 else 0 end'
}

started=$SECONDS
settled=false
while :; do
  dump_ledgers
  if [[ $(pending_count) == 0 ]]; then settled=true; break; fi
  ((SECONDS - started >= TIMEOUT)) && break
  sleep 1
done
waited=$((SECONDS - started))

# PG 거래 조회 계약(GET /v1/transactions?date=). UTC 자정을 넘긴 실행도 보게 전날·오늘을 합친다
pg_ok=true
{
  curl -fsS "$PG_URL/v1/transactions?date=$(date -u -d yesterday +%F)" || pg_ok=false
  curl -fsS "$PG_URL/v1/transactions?date=$(date -u +%F)" || pg_ok=false
} | jq -s 'add // []' > "$OUTDIR/ledger-pg.json" || pg_ok=false

# Redis: 그룹의 모든 좌석에 대해 스냅샷 값과 선점 키 존재 여부
mapfile -t SEATS < <(jq -r '[.pairs[] | .seatsA[], .seatsB[]] | unique[]' "$MANIFEST")
redis_cmds=()
for seat in "${SEATS[@]}"; do
  redis_cmds+=("HGET booking:seats:{$SCHEDULE_ID} $seat" "EXISTS booking:hold:{$SCHEDULE_ID}:$seat")
done
mapfile -t REDIS_OUT < <(printf '%s\n' "${redis_cmds[@]}" | docker compose exec -T redis redis-cli --raw | tr -d '\r')
{
  for i in "${!SEATS[@]}"; do
    printf '%s\t%s\t%s\n' "${SEATS[$i]}" "${REDIS_OUT[$((i * 2))]:-}" "${REDIS_OUT[$((i * 2 + 1))]:-}"
  done
} > "$OUTDIR/ledger-redis-seats.tsv"
jq -R -s 'split("\n") | map(select(length > 0) | split("\t") | {seat: (.[0] | tonumber), snapshot: .[1], holdKeyExists: (.[2] == "1")})' \
  "$OUTDIR/ledger-redis-seats.tsv" > "$OUTDIR/ledger-redis-seats.json"

# fake-pg 로그 기준 실제 전달 건수(설정률이 아니라 실제 발생 수)
if [[ -n $PG_LOG && -f $PG_LOG ]]; then
  rg -o 'scenario=\w+' "$PG_LOG" | sort | uniq -c | awk '{print $2 "\t" $1}' > "$OUTDIR/pg-scenarios.tsv" || true
  rg '웹훅 발송 완료' "$PG_LOG" | rg -o 'transactionId=\S+ eventId=\S+ type=\S+' > "$OUTDIR/pg-deliveries.txt" || true
  rg -o '환불 접수 transactionId=\S+' "$PG_LOG" | sed 's/.*transactionId=//' > "$OUTDIR/pg-refund-accepts.txt" || true
  rg -c '웹훅 발송 포기' "$PG_LOG" > "$OUTDIR/pg-abandoned.txt" || echo 0 > "$OUTDIR/pg-abandoned.txt"
else
  : > "$OUTDIR/pg-scenarios.tsv"; : > "$OUTDIR/pg-deliveries.txt"; : > "$OUTDIR/pg-refund-accepts.txt"; echo 0 > "$OUTDIR/pg-abandoned.txt"
fi

jq -n --slurpfile m "$MANIFEST" --slurpfile b "$OUTDIR/ledger-booking.json" --slurpfile p "$OUTDIR/ledger-payment.json" \
  --slurpfile pg "$OUTDIR/ledger-pg.json" --slurpfile r "$OUTDIR/ledger-redis-seats.json" \
  --rawfile scen "$OUTDIR/pg-scenarios.tsv" --rawfile deliveries "$OUTDIR/pg-deliveries.txt" --rawfile refunds "$OUTDIR/pg-refund-accepts.txt" \
  --argjson settled "$settled" --argjson waited "$waited" --argjson timeout "$TIMEOUT" --argjson pgOk "$pg_ok" \
  --argjson scheduleId "$SCHEDULE_ID" --argjson abandoned "$(cat "$OUTDIR/pg-abandoned.txt")" '
  ($b[0] | map({key: (.id | tostring), value: .}) | from_entries) as $bk
  | ($p[0] | map({key: (.orderId | tostring), value: .}) | from_entries) as $pk
  | ($pg[0] | group_by(.orderId) | map({key: .[0].orderId, value: .}) | from_entries) as $tk
  | ($r[0] | map({key: (.seat | tostring), value: .}) | from_entries) as $rk
  | ($refunds | split("\n") | map(select(length > 0))) as $refundAccepts
  | ($deliveries | split("\n") | map(select(length > 0) | capture("transactionId=(?<txn>\\S+) eventId=(?<event>\\S+) type=(?<type>\\S+)"))) as $dl
  | [ $m[0].pairs[] as $pair
      | ($pair.bookingA | tostring) as $ia | ($pair.bookingB | tostring) as $ib
      | ($bk[$ia]) as $ba | ($bk[$ib]) as $bb
      | (if $ba.status == "CONFIRMED" then "A" elif $bb.status == "CONFIRMED" then "B" else "none" end) as $winner
      | (if $winner == "A" then $ib elif $winner == "B" then $ia else null end) as $loserId
      | (if $winner == "A" then $ia elif $winner == "B" then $ib else null end) as $winnerId
      | (if $winner == "A" then $pair.seatsA else $pair.seatsB end) as $winnerSeats
      | (if $winner == "A" then $pair.seatsB else $pair.seatsA end) as $loserSeats
      | ($loserSeats - $winnerSeats) as $loserOnly
      | ($pk[$winnerId // ""]) as $pw | ($pk[$loserId // ""]) as $pl
      | ($tk[$winnerId // ""] // []) as $tw | ($tk[$loserId // ""] // []) as $tl
      | {pair: $pair.pair, winner: $winner,
         checks: {
           oneConfirmedOneCancelled: ($winner != "none" and $bk[$loserId].status == "CANCELLED" and $bk[$loserId].cancelReason == "SEAT_ALREADY_SOLD"),
           winnerSoldSeats: ($winner != "none" and $bk[$winnerId].sold == ($winnerSeats | length)),
           loserSoldSeatsZero: ($winner != "none" and $bk[$loserId].sold == 0),
           winnerPaymentSucceeded: ($winner != "none" and $pw.status == "SUCCEEDED" and ($pw.refundRequested | not)),
           loserPaymentRefunded: ($winner != "none" and $pl.status == "REFUNDED" and $pl.refundRequested and $pl.refunded),
           amountsMatchPayment: ($winner != "none" and $pw.amount == $bk[$winnerId].amount and $pl.amount == $bk[$loserId].amount),
           pgWinnerApprovedOnce: ($winner != "none" and ($tw | length) == 1 and $tw[0].status == "APPROVED" and $tw[0].amount == $pw.amount and $tw[0].refundedAt == null),
           pgLoserRefundedOnce: ($winner != "none" and ($tl | length) == 1 and $tl[0].status == "REFUNDED" and $tl[0].refundedAt != null and $tl[0].amount == $pl.amount),
           pgTxnMatchesPayment: ($winner != "none" and $tw[0].transactionId == $pw.txnId and $tl[0].transactionId == $pl.txnId),
           webhookEventsDeduplicated: ($winner != "none" and $pw.webhookEvents == 1 and $pl.webhookEvents == 2),
           redisWinnerSold: ($winner != "none" and ($winnerSeats | all(. as $s | $rk[($s | tostring)].snapshot == "SOLD"))),
           redisLoserOnlyAvailable: ($winner != "none" and ($loserOnly | all(. as $s | $rk[($s | tostring)].snapshot == "AVAILABLE"))),
           noLeftoverHoldKeys: ($winner != "none" and (($pair.seatsA + $pair.seatsB) | all(. as $s | $rk[($s | tostring)].holdKeyExists | not)))
         },
         refundAcceptsLoser: ($refundAccepts | map(select(. == ($tl[0].transactionId // "-"))) | length),
         refundAcceptsWinner: ($refundAccepts | map(select(. == ($tw[0].transactionId // "-"))) | length)}
    ] as $rows
  | ($rows | map(select(.checks | to_entries | any(.value | not))) ) as $failedRows
  | ($refundAccepts | group_by(.) | map(select(length > 1)) | length) as $dupRefundTxns
  | ($rows | map(select(.refundAcceptsWinner > 0)) | length) as $refundedWinners
  | ($dl | map(select(.type == "PAYMENT_APPROVED"))) as $approvedDl
  | ($approvedDl | map(.event) | unique | length) as $approvedUnique
  | ($scen | split("\n") | map(select(length > 0) | split("\t") | {key: .[0], value: (.[1] | tonumber)}) | from_entries) as $scenarios
  | {
      scheduleId: $scheduleId, pairs: ($rows | length), settled: $settled, waitedSeconds: $waited, timeoutSeconds: $timeout, pgQueryOk: $pgOk,
      winnerA: ($rows | map(select(.winner == "A")) | length), winnerB: ($rows | map(select(.winner == "B")) | length),
      confirmed: ($b[0] | map(select(.status == "CONFIRMED")) | length),
      cancelledSeatAlreadySold: ($b[0] | map(select(.status == "CANCELLED" and .cancelReason == "SEAT_ALREADY_SOLD")) | length),
      cancelledOther: ($b[0] | map(select(.status == "CANCELLED" and .cancelReason != "SEAT_ALREADY_SOLD")) | length),
      paymentsRefunded: ($p[0] | map(select(.status == "REFUNDED")) | length),
      paymentsFailed: ($p[0] | map(select(.status == "FAILED")) | length),
      refundIncomplete: ($p[0] | map(select(.status == "REFUND_REQUESTED")) | length) + (if $settled then 0 else 1 end),
      duplicateRefundTransactions: $dupRefundTxns, winnerRefundAccepts: $refundedWinners,
      pgScenarios: $scenarios,
      pgApprovedDeliveries: ($approvedDl | length), pgApprovedUniqueEvents: $approvedUnique,
      pgDuplicateApprovedDeliveries: (($approvedDl | length) - $approvedUnique),
      pgRefundDeliveries: ($dl | map(select(.type == "REFUND_COMPLETED")) | length), pgDeliveriesAbandoned: $abandoned,
      failedPairs: ($failedRows | map({pair, winner, failedChecks: (.checks | to_entries | map(select(.value | not) | .key))})),
      ok: ($settled and $pgOk and ($rows | length) > 0 and ($failedRows | length) == 0 and $dupRefundTxns == 0 and $refundedWinners == 0)
    }' > "$OUTDIR/compensation.json"

jq -r '"쌍 \(.pairs), 환불 대기 \(.waitedSeconds)초(상한 \(.timeoutSeconds)초, 정리 \(.settled)), A 승 \(.winnerA) / B 승 \(.winnerB)\n확정 \(.confirmed), SEAT_ALREADY_SOLD 취소 \(.cancelledSeatAlreadySold), 그 밖의 취소 \(.cancelledOther), REFUNDED \(.paymentsRefunded), 결제 FAILED \(.paymentsFailed)\n환불 미완료 \(.refundIncomplete), 중복 환불 거래 \(.duplicateRefundTransactions), 승자 환불 \(.winnerRefundAccepts)\nPG 승인 웹훅 전달 \(.pgApprovedDeliveries)건(고유 \(.pgApprovedUniqueEvents), 중복 전달 \(.pgDuplicateApprovedDeliveries)), 환불 웹훅 \(.pgRefundDeliveries), 포기 \(.pgDeliveriesAbandoned), 시나리오 \(.pgScenarios)\n실패한 쌍 \(.failedPairs | length)"' "$OUTDIR/compensation.json"
if [[ $(jq -r .ok "$OUTDIR/compensation.json") != true ]]; then
  jq -c '.failedPairs[:10][]' "$OUTDIR/compensation.json" >&2 || true
  printf '보상 검증 실패. %s 및 ledger-*.json 참고\n' "$OUTDIR/compensation.json" >&2
  exit 1
fi
