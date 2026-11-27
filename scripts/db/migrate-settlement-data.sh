#!/usr/bin/env bash
# 점검 창에서 core의 정산 사본·결과를 전용 DB로 복사한다. 절차는 docs/runbook.md §10 참고.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

source_db=
target_db=
container=
verify_only=false
while (($#)); do
  case "$1" in
    --source-db) source_db="${2:?--source-db 값이 필요합니다}"; shift 2 ;;
    --target-db) target_db="${2:?--target-db 값이 필요합니다}"; shift 2 ;;
    --postgres-container) container="${2:?--postgres-container 값이 필요합니다}"; shift 2 ;;
    --verify-only) verify_only=true; shift ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done

if [[ -z $container ]]; then
  set -a
  source .env
  set +a
  source_db="${source_db:-${TICKETING_POSTGRES_DB:?core DB 이름을 설정하세요}}"
  target_db="${target_db:-${TICKETING_SETTLEMENT_POSTGRES_DB:-settlement_db}}"
fi
for db_name in "$source_db" "$target_db"; do
  [[ $db_name =~ ^[A-Za-z0-9_]{1,63}$ ]] || { echo "DB 이름은 영문·숫자·밑줄만 허용됩니다." >&2; exit 2; }
done
[[ $source_db != "$target_db" ]] || { echo "원본과 대상 DB가 같습니다." >&2; exit 2; }
export TICKETING_SETTLEMENT_POSTGRES_USER="${TICKETING_SETTLEMENT_POSTGRES_USER:-settlement}"
export TICKETING_SETTLEMENT_POSTGRES_PASSWORD="${TICKETING_SETTLEMENT_POSTGRES_PASSWORD:?정산 DB 비밀번호를 설정하세요}"

credentials=(-e TICKETING_SETTLEMENT_POSTGRES_USER -e TICKETING_SETTLEMENT_POSTGRES_PASSWORD)
if [[ -n $container ]]; then
  executor=(docker exec -i "${credentials[@]}" "$container")
else
  executor=(docker compose exec -T "${credentials[@]}" postgres)
fi

"${executor[@]}" sh -s -- "$source_db" "$target_db" "$verify_only" <<'MIGRATION'
set -eu
source_db=$1
export target_db=$2 verify_only=$3 PGCLIENTENCODING=UTF8
snapshot_dir=$(mktemp -d)
trap 'rm -rf "$snapshot_dir"' EXIT
cd "$snapshot_dir"

# 구·신 스키마에서 같은 컬럼을 PK 순서로 내보낸다.
sale='booking_id, performance_id, organizer_id, schedule_id, amount, confirmed_at, received_at, list_amount, discount_amount'
refund='booking_id, performance_id, organizer_id, reason, cancelled_at, received_at'
daily='settlement_date, performance_id, organizer_id, sale_count, sale_amount, refund_count, refund_amount, net_amount, fee_rate, fee_amount, payout_amount, calculated_at, platform_discount_amount'

dump_tables() {
  side=$1
  printf "\\\\copy (SELECT %s FROM settlement.booking_sale ORDER BY booking_id) TO '%s_sale.csv' WITH (FORMAT csv)\n" "$sale" "$side"
  printf "\\\\copy (SELECT %s FROM settlement.booking_refund ORDER BY booking_id) TO '%s_refund.csv' WITH (FORMAT csv)\n" "$refund" "$side"
  printf "\\\\copy (SELECT %s FROM settlement.daily_settlement ORDER BY settlement_date, performance_id) TO '%s_daily.csv' WITH (FORMAT csv)\n" "$daily" "$side"
  printf '\\o %s_counts\n' "$side"
  cat <<'SQL'
SELECT 'booking_sale', count(*) FROM settlement.booking_sale;
SELECT 'booking_refund', count(*) FROM settlement.booking_refund;
SELECT 'daily_settlement', count(*) FROM settlement.daily_settlement;
\o
SQL
}

normalize() {
  printf '%s\n' "SET LOCAL TimeZone = 'UTC';" "SET LOCAL DateStyle = 'ISO, YMD';" "SET LOCAL lock_timeout = '15s';"
}

{
  printf '%s\n' 'BEGIN ISOLATION LEVEL READ COMMITTED;'
  normalize
  cat <<'SQL'
SELECT EXISTS (
  SELECT FROM pg_database WHERE datname = current_database() AND datdba = current_user::regrole
) AS target_owner \gset
\if :target_owner
\else
  DO $$ BEGIN RAISE EXCEPTION '대상 DB 소유 계정으로 접속해야 합니다'; END $$;
\endif
LOCK TABLE settlement.booking_sale, settlement.booking_refund, settlement.daily_settlement IN SHARE ROW EXCLUSIVE MODE;
SELECT EXISTS (SELECT FROM settlement.booking_sale)
    OR EXISTS (SELECT FROM settlement.booking_refund)
    OR EXISTS (SELECT FROM settlement.daily_settlement) AS populated \gset
\if :verify_only
\elif :populated
  \echo '대상에 데이터가 있어 적재 없이 대조합니다.'
\else
SQL
  printf "\\\\copy settlement.booking_sale (%s) FROM 'source_sale.csv' WITH (FORMAT csv)\n" "$sale"
  printf "\\\\copy settlement.booking_refund (%s) FROM 'source_refund.csv' WITH (FORMAT csv)\n" "$refund"
  printf "\\\\copy settlement.daily_settlement (%s) FROM 'source_daily.csv' WITH (FORMAT csv)\n" "$daily"
  printf '\\endif\n'
  dump_tables target
  cat <<'SQL'
\! cmp -s source_counts target_counts && cmp -s source_sale.csv target_sale.csv && cmp -s source_refund.csv target_refund.csv && cmp -s source_daily.csv target_daily.csv
\if :SHELL_ERROR
  DO $$ BEGIN RAISE EXCEPTION '원본과 대상의 건수 또는 정산 내용이 다릅니다'; END $$;
\endif
COMMIT;
SQL
} > load.sql

cat > load-target.sh <<'SH'
#!/bin/sh
PGPASSWORD="$TICKETING_SETTLEMENT_POSTGRES_PASSWORD" exec psql -X --no-password --host=127.0.0.1 \
  --username "$TICKETING_SETTLEMENT_POSTGRES_USER" --dbname "$target_db" \
  --set=ON_ERROR_STOP=1 --set="verify_only=$verify_only" -qAt --file=load.sql
SH

{
  printf '%s\n' 'BEGIN ISOLATION LEVEL REPEATABLE READ;'
  normalize
  cat <<'SQL'
LOCK TABLE settlement.booking_sale, settlement.booking_refund, settlement.daily_settlement,
    public.event_publication, batch.batch_job_execution, batch.batch_job_instance IN SHARE MODE;
SELECT EXISTS (
  SELECT FROM public.event_publication WHERE completion_date IS NULL AND event_type IN (
    'io.github.ticketing.booking.BookingConfirmed', 'io.github.ticketing.booking.BookingCancelled',
    'io.github.ticketing.payment.PaymentSucceeded', 'io.github.ticketing.payment.PaymentFailed',
    'io.github.ticketing.payment.PaymentRefunded'
  )
) OR EXISTS (
  SELECT FROM batch.batch_job_execution e JOIN batch.batch_job_instance i USING (job_instance_id)
  WHERE i.job_name = 'dailySettlementJob' AND e.status IN ('STARTING', 'STARTED', 'STOPPING', 'UNKNOWN')
) AS pending \gset
\if :pending
  DO $$ BEGIN RAISE EXCEPTION '미완료 정산 관련 발행 또는 실행 중인 일별 정산 배치가 있습니다'; END $$;
\endif
SQL
  dump_tables source
  cat <<'SQL'
\! sh ./load-target.sh
\if :SHELL_ERROR
  DO $$ BEGIN RAISE EXCEPTION '대상 이전 또는 대조에 실패했습니다'; END $$;
\endif
COMMIT;
SQL
} > export.sql

# 대상의 대조와 커밋까지 원본에 대한 쓰기를 막는다.
psql -X --no-password --username "$POSTGRES_USER" --dbname "$source_db" --set=ON_ERROR_STOP=1 -qAt --file=export.sql
printf '정산 대조 일치 (테이블|건수, PK 순 SHA-256):\n'
cat source_counts
sha256sum source_sale.csv source_refund.csv source_daily.csv
MIGRATION
