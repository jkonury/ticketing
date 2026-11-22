#!/usr/bin/env bash
# core DB의 payment 스키마 데이터를 payment_db로 옮기고 건수·금액·상태 체크섬을 대조한다. (ADR 0040 이전 절차 2~4단계)
# 사용: scripts/db/migrate-payment-data.sh [--verify-only] [--source-db 이름] [--target-db 이름]
#   기본 원본은 .env의 TICKETING_POSTGRES_DB, 기본 대상은 TICKETING_PAYMENT_POSTGRES_DB(없으면 payment_db)다.
# 점검 창 안에서 core·payment 쓰기를 멈추고 미완료 event_publication을 비운 뒤 실행한다. docs/runbook.md §9 참고.
#
# 이전 방식(컨테이너 안에서 실행, 덤프 파일은 컨테이너 임시 디렉터리에만 두고 종료 때 지운다)
#   1. 원본 세션을 REPEATABLE READ로 열어 스냅샷을 내보내고, 그 스냅샷으로 pg_dump와 원본 체크섬·미완료 이벤트를 같은 시점에 읽는다(시퀀스 값은 스냅샷에 묶이지 않아 조회 시점 값이다).
#   2. pg_dump가 정상 종료한 경우에만 대상 트랜잭션을 연다. advisory lock으로 같은 대상 실행을 직렬화하고, 대상 4테이블과
#      event_publication을 쓰기 차단 모드로 잠근 뒤 비어 있음·미완료 이벤트를 다시 검사한다.
#   3. 데이터를 적재하고 ALTER SEQUENCE ... RESTART로 시퀀스를 맞춘 뒤, COMMIT 전에 원본 스냅샷 체크섬과 대조한다. 다르면 롤백한다.
#   4. 커밋 뒤 새 세션에서 다시 대조한다(커밋 뒤에만 가능한 검사). 실패해도 커밋은 되돌려지지 않으므로 runbook의 복구 절차를 따른다.
# 테스트용 훅: MIGRATE_PG_DUMP(컨테이너 안 pg_dump 실행 파일 경로), MIGRATE_LOAD_PSQL(적재용 psql 실행 파일 경로), MIGRATE_STATUS_LOCK_TIMEOUT(적재 실패 뒤 종료 확인 잠금 대기, 기본 30s). 운영 실행에서는 설정하지 않는다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

VERIFY_ONLY=false
SOURCE_DB_OPT=""
TARGET_DB_OPT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --verify-only) VERIFY_ONLY=true; shift ;;
    --source-db) SOURCE_DB_OPT="${2:?--source-db 값이 필요합니다}"; shift 2 ;;
    --target-db) TARGET_DB_OPT="${2:?--target-db 값이 필요합니다}"; shift 2 ;;
    -h|--help) sed -n '2,5p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done

# .env는 값을 출력하지 않고 환경 변수로만 읽는다
set -a
source .env
set +a
export TICKETING_PAYMENT_POSTGRES_USER="${TICKETING_PAYMENT_POSTGRES_USER:-payment}"
export TICKETING_PAYMENT_POSTGRES_PASSWORD="${TICKETING_PAYMENT_POSTGRES_PASSWORD:?결제 DB 비밀번호를 설정하세요}"
SOURCE_DB="${SOURCE_DB_OPT:-${TICKETING_POSTGRES_DB:?TICKETING_POSTGRES_DB를 설정하세요}}"
TARGET_DB="${TARGET_DB_OPT:-${TICKETING_PAYMENT_POSTGRES_DB:-payment_db}}"
export MIGRATE_SOURCE_DB="$SOURCE_DB" MIGRATE_TARGET_DB="$TARGET_DB"

for name in "$SOURCE_DB" "$TARGET_DB"; do
  [[ $name =~ ^[A-Za-z0-9_]+$ ]] || { echo "DB 이름이 올바르지 않습니다(영문·숫자·밑줄만 허용): $name" >&2; exit 2; }
done
[[ $SOURCE_DB != "$TARGET_DB" ]] || { echo '원본과 대상 DB가 같습니다' >&2; exit 2; }
[[ ${MIGRATE_STATUS_LOCK_TIMEOUT:-30s} =~ ^[0-9]+(ms|s|min)$ ]] || { echo "MIGRATE_STATUS_LOCK_TIMEOUT 형식이 올바르지 않습니다(예: 30s): $MIGRATE_STATUS_LOCK_TIMEOUT" >&2; exit 2; }

# 체크섬 문자열 표현이 DB·역할 설정에 따라 달라지지 않도록 출력 형식을 고정한다.
# 금액 합계·행 해시는 행을 텍스트로 바꿔 계산하며, COPY가 numeric 스케일을 그대로 옮기므로 별도 정규화는 하지 않는다.
export MIGRATE_SESSION_SQL="SET TimeZone = 'UTC'; SET DateStyle = 'ISO, YMD'; SET IntervalStyle = 'postgres'; SET extra_float_digits = 1"

# 테이블별 한 줄: 이름|건수|금액 합계|상태(유형) 분포|행 전체를 기본 키 순으로 이은 텍스트의 md5. 같은 질의를 원본·대상에서 쓴다.
read -r -d '' MIGRATE_CHECKSUM_SQL <<'SQL' || true
SELECT line FROM (
  SELECT 'payment.payment|' || count(*) || '|' || coalesce(sum(amount), 0) || '|'
    || coalesce((SELECT string_agg(status || '=' || n, ',' ORDER BY status COLLATE "C") FROM (SELECT status, count(*) n FROM payment.payment GROUP BY status) s), '') || '|'
    || md5(coalesce(string_agg(t::text, E'\n' ORDER BY id), '')) AS line
  FROM payment.payment t
  UNION ALL
  SELECT 'payment.webhook_event|' || count(*) || '|0|'
    || coalesce((SELECT string_agg(type || '=' || n, ',' ORDER BY type COLLATE "C") FROM (SELECT type, count(*) n FROM payment.webhook_event GROUP BY type) s), '') || '|'
    || md5(coalesce(string_agg(t::text, E'\n' ORDER BY event_id COLLATE "C"), ''))
  FROM payment.webhook_event t
  UNION ALL
  SELECT 'payment.pg_transaction_snapshot|' || count(*) || '|' || coalesce(sum(amount), 0) || '|'
    || coalesce((SELECT string_agg(status || '=' || n, ',' ORDER BY status COLLATE "C") FROM (SELECT status, count(*) n FROM payment.pg_transaction_snapshot GROUP BY status) s), '') || '|'
    || md5(coalesce(string_agg(t::text, E'\n' ORDER BY target_date, transaction_id COLLATE "C"), ''))
  FROM payment.pg_transaction_snapshot t
  UNION ALL
  SELECT 'payment.reconciliation_mismatch|' || count(*) || '|'
    || (coalesce(sum(coalesce(pg_amount, 0)), 0) + coalesce(sum(coalesce(internal_amount, 0)), 0)) || '|'
    || coalesce((SELECT string_agg(mismatch_type || '=' || n, ',' ORDER BY mismatch_type COLLATE "C") FROM (SELECT mismatch_type, count(*) n FROM payment.reconciliation_mismatch GROUP BY mismatch_type) s), '') || '|'
    || md5(coalesce(string_agg(t::text, E'\n' ORDER BY target_date, order_id, mismatch_type COLLATE "C"), ''))
  FROM payment.reconciliation_mismatch t
) q ORDER BY line COLLATE "C"
SQL
export MIGRATE_CHECKSUM_SQL

# identity 시퀀스가 다음에 발급할 값. is_called=false면 last_value 자체가 다음 값이다.
export MIGRATE_SEQ_SQL="SELECT CASE WHEN s.is_called THEN s.last_value + p.increment_by ELSE s.last_value END
  FROM payment.payment_id_seq s CROSS JOIN pg_sequences p WHERE p.schemaname = 'payment' AND p.sequencename = 'payment_id_seq'"

# 컨테이너 안에서 실행한다. 원본은 core 계정(소켓 접속), 대상은 payment 계정으로 접속한다.
# 비밀번호는 환경 변수(-e)로만 전달하고 명령행 인자에 두지 않는다.
# 인자: $1=src|dst, $2=SQL(psql -c). 결과는 -qAt 형식으로 stdout에 낸다.
psql_db() {
  docker compose exec -T -e MIGRATE_SOURCE_DB -e MIGRATE_TARGET_DB \
    -e TICKETING_PAYMENT_POSTGRES_USER -e TICKETING_PAYMENT_POSTGRES_PASSWORD \
    postgres sh -c '
      if [ "$1" = src ]; then
        exec psql -X --no-password -v ON_ERROR_STOP=1 -qAt -U "$POSTGRES_USER" -d "$MIGRATE_SOURCE_DB" -c "$2"
      else
        PGPASSWORD="$TICKETING_PAYMENT_POSTGRES_PASSWORD" exec psql -X --no-password -v ON_ERROR_STOP=1 -qAt \
          -U "$TICKETING_PAYMENT_POSTGRES_USER" -d "$MIGRATE_TARGET_DB" -c "$2"
      fi' sh "$1" "$2"
}

fail() { echo "중단: $*" >&2; exit 1; }

# 테이블별 건수. 테이블이 없으면 실패한다.
table_counts() {
  psql_db "$1" "SELECT 'payment.payment', count(*) FROM payment.payment
    UNION ALL SELECT 'payment.webhook_event', count(*) FROM payment.webhook_event
    UNION ALL SELECT 'payment.pg_transaction_snapshot', count(*) FROM payment.pg_transaction_snapshot
    UNION ALL SELECT 'payment.reconciliation_mismatch', count(*) FROM payment.reconciliation_mismatch"
}

pending_events() {
  psql_db "$1" "SELECT CASE WHEN to_regclass('public.event_publication') IS NULL THEN 0
    ELSE (SELECT count(*) FROM public.event_publication WHERE completion_date IS NULL) END"
}

# 원본과 대상을 같은 질의로 대조한다(--verify-only 전용. 이전 중의 대조는 컨테이너 스크립트가 스냅샷 기준으로 한다).
verify_live() {
  local src dst src_next dst_next dst_max
  src="$(psql_db src "$MIGRATE_SESSION_SQL; $MIGRATE_CHECKSUM_SQL")" || fail '원본 체크섬 계산에 실패했습니다'
  dst="$(psql_db dst "$MIGRATE_SESSION_SQL; $MIGRATE_CHECKSUM_SQL")" || fail '대상 체크섬 계산에 실패했습니다'
  echo '== 대조 (테이블|건수|금액 합계|상태 분포|행 해시) =='
  echo "원본($SOURCE_DB):"; printf '%s\n' "$src" | sed 's/^/  /'
  echo "대상($TARGET_DB):"; printf '%s\n' "$dst" | sed 's/^/  /'
  if [[ "$src" != "$dst" ]]; then
    echo '불일치한 테이블:' >&2
    diff <(echo "$src") <(echo "$dst") | grep '^[<>]' | cut -d'|' -f1 | sed 's/^[<>] /  /' | sort -u >&2 || true
    fail '원본과 대상의 건수·체크섬이 다릅니다'
  fi
  src_next="$(psql_db src "$MIGRATE_SEQ_SQL")"
  dst_next="$(psql_db dst "$MIGRATE_SEQ_SQL")"
  dst_max="$(psql_db dst 'SELECT coalesce(max(id), 0) FROM payment.payment')"
  [[ $dst_next -gt $dst_max ]] || fail "대상 payment_id_seq 다음 발급 값($dst_next)이 최대 id($dst_max) 이하입니다(ID 충돌 위험)"
  [[ $dst_next -ge $src_next ]] || fail "대상 payment_id_seq 다음 발급 값($dst_next)이 원본($src_next)보다 작습니다"
  echo "대조 일치: 건수·금액·상태·행 해시, 시퀀스 다음 발급 값(대상 $dst_next ≥ 원본 $src_next, 최대 id $dst_max)"
}

if [[ "$VERIFY_ONLY" == true ]]; then
  echo "대조만 수행합니다: $SOURCE_DB -> $TARGET_DB"
  verify_live
  exit 0
fi

echo "== 전제 검사: $SOURCE_DB -> $TARGET_DB =="
src_counts="$(table_counts src)" || fail "원본($SOURCE_DB)의 payment 테이블을 읽지 못했습니다"
dst_counts="$(table_counts dst)" || fail "대상($TARGET_DB)의 payment 테이블을 읽지 못했습니다. payment 앱 Flyway가 먼저 실행됐는지, DB 이름이 맞는지 확인하세요"

# 대상에 이미 데이터가 있으면 덮어쓰지 않고 중단한다
non_empty="$(echo "$dst_counts" | awk -F'|' '$2 > 0 {printf "%s(%s건) ", $1, $2}')"
[[ -z "$non_empty" ]] || fail "대상 DB에 이미 데이터가 있습니다: $non_empty. 덮어쓰지 않습니다. 대상이 비어 있는 새 DB에서 실행하세요"

# 점검 창 전제: 양쪽 모두 쓰기가 멈추고 미완료 이벤트가 비어 있어야 한다. 이 검사는 빠른 실패용이고,
# 같은 검사를 적재 트랜잭션 안(잠금 획득 뒤)과 원본 스냅샷 시점에 다시 한다.
src_pending="$(pending_events src)"
dst_pending="$(pending_events dst)"
if [[ "$src_pending" != 0 || "$dst_pending" != 0 ]]; then
  fail "미완료 event_publication이 남아 있습니다(원본 $src_pending건, 대상 $dst_pending건). 점검 창에서 쓰기를 멈추고 이벤트가 모두 완료되거나 처리될 때까지 기다린 뒤 다시 실행하세요(docs/runbook.md 결제 DB 이전)"
fi

echo '원본 건수:'; printf '%s\n' "$src_counts" | sed 's/^/  /'
total="$(echo "$src_counts" | awk -F'|' '{s += $2} END {print s + 0}')"
[[ "$total" -gt 0 ]] || echo '경고: 원본 4개 테이블이 모두 비어 있습니다. 이전할 데이터가 없습니다'

echo '== 이전 (스냅샷 pg_dump --data-only -> 잠금·재검사·적재·시퀀스·커밋 전 대조 -> COMMIT -> 커밋 뒤 대조) =='
read -r -d '' CONTAINER_SCRIPT <<'CONTAINER' || true
set -euo pipefail
WORK="$(mktemp -d)"
SRC_OPEN=false
cleanup() {
  if [ "$SRC_OPEN" = true ]; then eval "exec ${SRC[1]}>&-" 2>/dev/null || true; kill "$SRC_PID" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT
die() { echo "중단: $*" >&2; exit 1; }

tgt_psql() { PGPASSWORD="$TICKETING_PAYMENT_POSTGRES_PASSWORD" psql -X --no-password -v ON_ERROR_STOP=1 -qAt \
  -U "$TICKETING_PAYMENT_POSTGRES_USER" -d "$MIGRATE_TARGET_DB" "$@"; }

# 원본 스냅샷 세션: 열어 둔 채 pg_dump가 같은 스냅샷을 쓰게 한다
coproc SRC { psql -X --no-password -v ON_ERROR_STOP=1 -qAt -U "$POSTGRES_USER" -d "$MIGRATE_SOURCE_DB"; }
SRC_OPEN=true
src_run() {
  printf '%s;\nSELECT %s;\n' "$1" "'__END__'" >&"${SRC[1]}"
  local line seen=false
  while IFS= read -r line <&"${SRC[0]}"; do
    if [ "$line" = __END__ ]; then seen=true; break; fi
    printf '%s\n' "$line"
  done
  [ "$seen" = true ] || die '원본 스냅샷 세션이 비정상 종료했습니다'
}

src_run "$MIGRATE_SESSION_SQL" >/dev/null
src_run 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY' >/dev/null
SNAPSHOT="$(src_run 'SELECT pg_export_snapshot()')"
[[ $SNAPSHOT =~ ^[0-9A-Fa-f-]+$ ]] || die "스냅샷 ID를 얻지 못했습니다: $SNAPSHOT"

# 원본 미완료 이벤트와 체크섬은 스냅샷 시점(덤프와 같은 시점)에 읽는다. 시퀀스 상태는 MVCC 스냅샷으로 고정되지 않아 조회 시점 값이므로 쓰기 중지에 의존한다
SRC_PENDING="$(src_run "SELECT CASE WHEN to_regclass('public.event_publication') IS NULL THEN 0 ELSE (SELECT count(*) FROM public.event_publication WHERE completion_date IS NULL) END")"
[ "$SRC_PENDING" = 0 ] || die "미완료 event_publication이 남아 있습니다(원본 스냅샷 시점 ${SRC_PENDING}건). 점검 창에서 쓰기를 멈추고 이벤트를 모두 처리한 뒤 다시 실행하세요"
src_run "$MIGRATE_CHECKSUM_SQL" > "$WORK/src.sum"
SRC_NEXT="$(src_run "$MIGRATE_SEQ_SQL")"
[[ $SRC_NEXT =~ ^[0-9]+$ ]] || die "원본 시퀀스 다음 값을 읽지 못했습니다: $SRC_NEXT"

# 덤프는 파일로 받아 종료 코드를 확인한 뒤에만 적재한다(파이프로 바로 넘기면 덤프 실패가 적재 커밋을 막지 못한다)
"${MIGRATE_PG_DUMP:-pg_dump}" --no-password -U "$POSTGRES_USER" -d "$MIGRATE_SOURCE_DB" --snapshot="$SNAPSHOT" --data-only \
  -t payment.payment -t payment.webhook_event -t payment.pg_transaction_snapshot -t payment.reconciliation_mismatch \
  > "$WORK/dump.sql" || die 'pg_dump가 실패했습니다. 대상에는 아무것도 반영하지 않았습니다'
[ -s "$WORK/dump.sql" ] || die 'pg_dump 결과가 비어 있습니다. 대상에는 아무것도 반영하지 않았습니다'

# 덤프의 setval은 트랜잭션으로 되돌릴 수 없으므로 적용하지 않고 걸러낸다. 적재 트랜잭션에서 ALTER SEQUENCE로 설정한다
grep -v "^SELECT pg_catalog.setval('payment.payment_id_seq'," "$WORK/dump.sql" > "$WORK/dump.nosetval.sql" || true
if grep -q '^SELECT pg_catalog.setval(' "$WORK/dump.nosetval.sql"; then die '예상하지 못한 시퀀스가 덤프에 포함돼 있습니다'; fi

# 스냅샷 세션을 닫는다. 이후 원본은 읽지 않는다
src_run 'COMMIT' >/dev/null
eval "exec ${SRC[1]}>&-"
wait "$SRC_PID" || true
SRC_OPEN=false

# 적재 SQL: 한 트랜잭션. COMMIT에 이르지 못하면(오류·중단) 세션이 끝나며 롤백된다
{
  cat <<'HEAD'
\set ON_ERROR_STOP on
-- 대상 DB·역할의 기본 격리 수준이 REPEATABLE READ여도 잠금 뒤 재검사가 새 스냅샷을 보도록 READ COMMITTED를 명시한다
BEGIN ISOLATION LEVEL READ COMMITTED;
SET LOCAL lock_timeout = '30s';
-- 같은 대상을 향한 실행 직렬화
SELECT pg_advisory_xact_lock(hashtextextended('ticketing.migrate-payment-data:' || current_database(), 0));
-- 쓰기 차단 모드로 잠근 뒤 재검사한다(사전 검사 이후의 쓰기를 막는다)
LOCK TABLE payment.payment, payment.webhook_event, payment.pg_transaction_snapshot, payment.reconciliation_mismatch IN EXCLUSIVE MODE;
SELECT to_regclass('public.event_publication') IS NOT NULL AS has_event_publication \gset
\if :has_event_publication
LOCK TABLE public.event_publication IN EXCLUSIVE MODE;
\endif
DO $$
DECLARE
  pending bigint := 0;
BEGIN
  IF EXISTS (SELECT 1 FROM payment.payment) OR EXISTS (SELECT 1 FROM payment.webhook_event)
     OR EXISTS (SELECT 1 FROM payment.pg_transaction_snapshot) OR EXISTS (SELECT 1 FROM payment.reconciliation_mismatch) THEN
    RAISE EXCEPTION '적재 직전 재검사: 대상 테이블에 데이터가 있습니다. 덮어쓰지 않습니다.';
  END IF;
  IF to_regclass('public.event_publication') IS NOT NULL THEN
    EXECUTE 'SELECT count(*) FROM public.event_publication WHERE completion_date IS NULL' INTO pending;
    IF pending > 0 THEN
      RAISE EXCEPTION '적재 직전 재검사: 대상에 미완료 event_publication이 %건 있습니다.', pending;
    END IF;
  END IF;
END
$$;
HEAD
  printf "\\\\i '%s'\n" "$WORK/dump.nosetval.sql"
  cat <<'SEQ'
-- 시퀀스는 적재 트랜잭션 안에서 설정한다. ALTER SEQUENCE ... RESTART는 롤백되면 함께 원상태로 돌아간다
SELECT format('ALTER SEQUENCE payment.payment_id_seq RESTART WITH %s',
SEQ
  printf "  greatest((SELECT coalesce(max(id), 0) FROM payment.payment) + 1, %s::bigint)) \\\\gexec\n" "$SRC_NEXT"
  printf '%s;\n' "$MIGRATE_SESSION_SQL"
  printf "\\\\set src_sum \`cat '%s'\`\n" "$WORK/src.sum"
  printf "SELECT coalesce(string_agg(line, E'\\\\n' ORDER BY line COLLATE \"C\"), '') = :'src_sum' AS checksum_ok FROM (\n%s\n) c \\\\gset\n" "$MIGRATE_CHECKSUM_SQL"
  cat <<'CHECK1'
\if :checksum_ok
\else
  DO $$ BEGIN RAISE EXCEPTION '커밋 전 대조 실패: 적재한 데이터가 원본 스냅샷과 다릅니다. 롤백합니다.'; END $$;
\endif
CHECK1
  printf "SELECT n > m AND n >= %s AS seq_ok FROM (SELECT (%s) AS n, (SELECT coalesce(max(id), 0) FROM payment.payment) AS m) x \\\\gset\n" "$SRC_NEXT" "$MIGRATE_SEQ_SQL"
  cat <<'CHECK2'
\if :seq_ok
\else
  DO $$ BEGIN RAISE EXCEPTION '커밋 전 대조 실패: 시퀀스 다음 발급 값이 최대 id 또는 원본 값보다 작습니다. 롤백합니다.'; END $$;
\endif
COMMIT;
CHECK2
} > "$WORK/load.sql"

# 적재 전 대상 시퀀스 상태. 원본 4테이블이 비어 있으면 적재가 시퀀스만 바꾸므로, 실패 뒤 커밋 여부 판별에 쓴다
SEQ_BEFORE="$(tgt_psql -c "SELECT last_value || ',' || is_called FROM payment.payment_id_seq")" \
  || die '적재 전 대상 시퀀스를 읽지 못했습니다. 대상에는 아무것도 반영하지 않았습니다'

# 적재 트랜잭션이 끝난 뒤의 대상 상태를 읽는다. 적재 트랜잭션이 쥔 advisory lock을 새 세션에서 얻어(트랜잭션 종료까지 대기)
# 커밋 또는 롤백이 확정된 뒤의 새 스냅샷으로 행·시퀀스를 읽는다. 잠금을 시간 안에 얻지 못하면 실패한다(결과 미확인)
status_after_load() {
  tgt_psql -f - <<STATUS_SQL
BEGIN;
SET LOCAL lock_timeout = '${MIGRATE_STATUS_LOCK_TIMEOUT:-30s}';
SELECT pg_advisory_xact_lock(hashtextextended('ticketing.migrate-payment-data:' || current_database(), 0)) \gset
SELECT ((SELECT count(*) FROM payment.payment) + (SELECT count(*) FROM payment.webhook_event)
        + (SELECT count(*) FROM payment.pg_transaction_snapshot) + (SELECT count(*) FROM payment.reconciliation_mismatch))
       || '|' || (SELECT last_value || ',' || is_called FROM payment.payment_id_seq) || '|' || ($MIGRATE_SEQ_SQL);
COMMIT;
STATUS_SQL
}

# MIGRATE_LOAD_PSQL은 테스트가 커밋 응답 유실 등을 흉내 내는 용도다
LOAD_RC=0
PGPASSWORD="$TICKETING_PAYMENT_POSTGRES_PASSWORD" "${MIGRATE_LOAD_PSQL:-psql}" -X --no-password -q -v ON_ERROR_STOP=1 \
  -U "$TICKETING_PAYMENT_POSTGRES_USER" -d "$MIGRATE_TARGET_DB" -o /dev/null -f "$WORK/load.sql" || LOAD_RC=$?
if [ "$LOAD_RC" -ne 0 ]; then
  # psql 실패가 곧 롤백은 아니다. 서버가 COMMIT을 처리한 뒤 응답만 잃었거나 서버의 적재 트랜잭션이 아직 끝나지 않았을 수 있다.
  # 대상 상태를 읽기 전에 적재 트랜잭션의 종료를 확인해 구분한다
  echo "적재 psql이 비정상 종료했습니다(종료 코드 $LOAD_RC). 적재 트랜잭션의 종료를 기다려 커밋 여부를 확인합니다" >&2
  STATE="$(status_after_load)" \
    || die "커밋 결과를 확인할 수 없는 오류입니다: 대상($MIGRATE_TARGET_DB)의 적재 트랜잭션의 종료를 확인하지 못했습니다(잠금 대기 시간 초과 또는 접속 실패). 적재가 아직 진행 중이거나 커밋됐을 수 있습니다. 쓰기 차단을 유지하고 대상의 활동 세션과 네 테이블을 직접 확인한 뒤 판단하세요(docs/runbook.md §9 커밋 결과를 알 수 없을 때)"
  DST_ROWS="${STATE%%|*}"; REST="${STATE#*|}"; SEQ_NOW="${REST%%|*}"; NEXT_NOW="${REST#*|}"
  if [ "$DST_ROWS" = 0 ] && [ "$SEQ_NOW" = "$SEQ_BEFORE" ]; then
    die '적재 트랜잭션이 실패했습니다. 적재 트랜잭션이 끝난 뒤 대상 4테이블과 시퀀스가 적재 전 상태와 같아 반영된 변경이 없음을 확인했습니다. 원인을 고치고 다시 실행할 수 있습니다'
  fi
  if [ "$DST_ROWS" = 0 ]; then
    if [[ $NEXT_NOW =~ ^[0-9]+$ ]] && [ "$NEXT_NOW" -ge "$SRC_NEXT" ]; then
      die "커밋 결과를 확인할 수 없는 오류입니다: 대상($MIGRATE_TARGET_DB)의 4테이블은 비어 있지만 시퀀스가 적재 전($SEQ_BEFORE)과 다르고 다음 발급 값($NEXT_NOW)이 원본($SRC_NEXT) 이상입니다. 원본 4테이블이 비어 있어 시퀀스만 반영하는 적재가 커밋되고 응답만 잃은 것으로 보입니다. 다시 실행하지 말고 쓰기 차단을 유지한 채 --verify-only로 재대조한 뒤 전환하세요(docs/runbook.md §9)"
    fi
    die "커밋 결과를 확인할 수 없는 오류입니다: 대상($MIGRATE_TARGET_DB)의 4테이블은 비어 있지만 시퀀스가 적재 전($SEQ_BEFORE)과 달라($SEQ_NOW) 상태를 판별할 수 없습니다. 쓰기 차단을 유지하고 직접 확인하세요(docs/runbook.md §9)"
  fi
  if tgt_psql -c "$MIGRATE_SESSION_SQL; $MIGRATE_CHECKSUM_SQL" > "$WORK/dst.sum" && cmp -s "$WORK/src.sum" "$WORK/dst.sum"; then
    die "커밋 결과를 확인할 수 없는 오류입니다: 대상($MIGRATE_TARGET_DB)에 데이터가 반영돼 있고 원본 스냅샷과 건수·체크섬이 일치합니다. 커밋 응답만 잃은 것으로 보입니다. 다시 실행하지 말고 쓰기 차단을 유지한 채 --verify-only로 재대조한 뒤 전환하세요(docs/runbook.md §9)"
  fi
  die "커밋 결과를 확인할 수 없는 오류입니다: 대상($MIGRATE_TARGET_DB)에 데이터가 있으나 원본 스냅샷과 다릅니다(다른 세션의 쓰기이거나 일부만 반영된 상태). 다시 실행하지 말고 쓰기 차단을 유지한 채 대상의 네 테이블을 확인한 뒤 판단하세요(docs/runbook.md §9)"
fi
echo '커밋 완료. 커밋 뒤 대조를 수행합니다'

# 커밋 뒤에만 할 수 있는 검사: 새 세션에서 다시 계산한 대상 체크섬과 시퀀스
tgt_psql -c "$MIGRATE_SESSION_SQL; $MIGRATE_CHECKSUM_SQL" > "$WORK/dst.sum"
echo '== 대조 (테이블|건수|금액 합계|상태 분포|행 해시) =='
echo "원본($MIGRATE_SOURCE_DB, 스냅샷 시점):"; sed 's/^/  /' "$WORK/src.sum"
echo "대상($MIGRATE_TARGET_DB):"; sed 's/^/  /' "$WORK/dst.sum"
if ! cmp -s "$WORK/src.sum" "$WORK/dst.sum"; then
  echo '불일치한 테이블:' >&2
  diff "$WORK/src.sum" "$WORK/dst.sum" | grep '^[<>]' | cut -d'|' -f1 | sed 's/^[<>] /  /' | sort -u >&2 || true
  die '커밋 뒤 대조 실패: 원본과 대상의 건수·체크섬이 다릅니다. 이미 커밋됐습니다. docs/runbook.md §9의 복구 절차를 따르세요'
fi
DST_NEXT="$(tgt_psql -c "$MIGRATE_SEQ_SQL")"
DST_MAX="$(tgt_psql -c 'SELECT coalesce(max(id), 0) FROM payment.payment')"
{ [ "$DST_NEXT" -gt "$DST_MAX" ] && [ "$DST_NEXT" -ge "$SRC_NEXT" ]; } \
  || die "커밋 뒤 대조 실패: 대상 시퀀스 다음 발급 값($DST_NEXT)이 최대 id($DST_MAX) 이하이거나 원본($SRC_NEXT)보다 작습니다. 이미 커밋됐습니다. docs/runbook.md §9의 복구 절차를 따르세요"
echo "대조 일치: 건수·금액·상태·행 해시, 시퀀스 다음 발급 값(대상 $DST_NEXT ≥ 원본 $SRC_NEXT, 최대 id $DST_MAX)"
CONTAINER

docker compose exec -T -e MIGRATE_SOURCE_DB -e MIGRATE_TARGET_DB \
  -e TICKETING_PAYMENT_POSTGRES_USER -e TICKETING_PAYMENT_POSTGRES_PASSWORD \
  -e MIGRATE_SESSION_SQL -e MIGRATE_CHECKSUM_SQL -e MIGRATE_SEQ_SQL ${MIGRATE_PG_DUMP:+-e MIGRATE_PG_DUMP} ${MIGRATE_LOAD_PSQL:+-e MIGRATE_LOAD_PSQL} ${MIGRATE_STATUS_LOCK_TIMEOUT:+-e MIGRATE_STATUS_LOCK_TIMEOUT} \
  postgres bash -c "$CONTAINER_SCRIPT" migrate || fail '이전이 완료되지 않았습니다. 위 메시지를 확인하세요'
echo '이전 완료'
