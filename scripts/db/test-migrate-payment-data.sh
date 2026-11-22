#!/usr/bin/env bash
# migrate-payment-data.sh를 임시 DB로 검증한다. 실제 payment_db는 읽지도 쓰지도 않는다(스키마만 복제).
#   기본: 임시 DB 보호·중간 실패 정리, 이전 성공, 대상이 비어 있지 않음, 미완료 이벤트, 체크섬 불일치, 덤프 실패·잘린 덤프, 커밋 응답 유실(빈 원본·적재 트랜잭션 진행 중 포함), 시퀀스,
#         적재 트랜잭션 안의 재검사·직렬화(대상 기본 격리 수준 READ COMMITTED·REPEATABLE READ 둘 다)를 확인한다.
#   --full: 추가로 실제 core DB(읽기만)를 원본으로 임시 대상에 이전해 대용량 경로를 확인한다.
#   --flyway-boot: 추가로 payment-batch.jar를 Job 없이 임시 DB에 띄워 Flyway만 적용되고 Kafka 소비가 시작되지 않음을 확인한다.
# 사전 조건: docker compose의 postgres가 떠 있고 .env가 있다. 임시 DB는 이 스크립트가 만든 것만 지우며, 정리 실패는 종료 코드에 반영한다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
MIGRATE="$ROOT/scripts/db/migrate-payment-data.sh"

FULL=false
FLYWAY_BOOT=false
COLLISION_PROBE=false
FAIL_PROBE=false
for arg in "$@"; do
  case "$arg" in
    --full) FULL=true ;;
    --flyway-boot) FLYWAY_BOOT=true ;;
    --collision-probe) COLLISION_PROBE=true ;; # 이름 충돌 자체 점검용(내부 사용)
    --fail-probe) FAIL_PROBE=true ;; # 임시 DB를 만든 뒤 일부러 실패해 정리를 점검하는 용도(내부 사용)
    *) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
  esac
done

set -a
source .env
set +a
export TICKETING_PAYMENT_POSTGRES_USER="${TICKETING_PAYMENT_POSTGRES_USER:-payment}"
PAYMENT_USER="$TICKETING_PAYMENT_POSTGRES_USER"
REAL_CORE_DB="${TICKETING_POSTGRES_DB:?TICKETING_POSTGRES_DB를 설정하세요}"
REAL_PAYMENT_DB="${TICKETING_PAYMENT_POSTGRES_DB:-payment_db}"
# 무작위 접미사. TEST_DB_SUFFIX는 이름 충돌 자체 점검에서만 쓴다
SUFFIX="${TEST_DB_SUFFIX:-$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')}"
[[ $SUFFIX =~ ^[a-z0-9]+$ ]] || { echo "접미사가 올바르지 않습니다: $SUFFIX" >&2; exit 2; }
LOG="$(mktemp)"
FLAG_DIR="$(mktemp -d)"
FAKE_DUMP_FAIL="/tmp/mig-fake-dump-fail-$SUFFIX.sh"
FAKE_DUMP_TRUNC="/tmp/mig-fake-dump-trunc-$SUFFIX.sh"
FAKE_PSQL_LOST="/tmp/mig-fake-psql-lost-$SUFFIX.sh"
FAKE_PSQL_LOST_DIFF="/tmp/mig-fake-psql-lostdiff-$SUFFIX.sh"
FAKE_PSQL_NOLOAD="/tmp/mig-fake-psql-noload-$SUFFIX.sh"
FAKE_PSQL_SLOW="/tmp/mig-fake-psql-slow-$SUFFIX.sh"

# core 계정(슈퍼유저)으로 SQL을 실행한다. 인자: DB 이름, SQL
admin_sql() {
  docker compose exec -T postgres sh -c 'psql -X --no-password -v ON_ERROR_STOP=1 -qAt -U "$POSTGRES_USER" -d "$1" -c "$2"' sh "$1" "$2"
}
# payment 계정으로 SQL을 실행한다. 인자: DB 이름, SQL
payment_sql() {
  docker compose exec -T -e TICKETING_PAYMENT_POSTGRES_USER -e TICKETING_PAYMENT_POSTGRES_PASSWORD postgres sh -c \
    'PGPASSWORD="$TICKETING_PAYMENT_POSTGRES_PASSWORD" psql -X --no-password -v ON_ERROR_STOP=1 -qAt -U "$TICKETING_PAYMENT_POSTGRES_USER" -d "$1" -c "$2"' sh "$1" "$2"
}

# ---- 임시 DB 관리: 이 스크립트가 CREATE에 성공한 DB만 TRACKED에 넣고, 정리도 그것만 한다 ----
TRACKED=()
TEMPLATE_MARKED=() # IS_TEMPLATE을 true로 바꾼 DB. 실패 경로에서도 정리 전에 원복한다
assert_temp_name() {
  local name="$1"
  [[ $name =~ ^tmp_mig_[a-z0-9_]+$ ]] || { echo "임시 DB 이름 규칙(tmp_mig_*)에 맞지 않습니다: $name" >&2; return 1; }
  if [[ $name == "$REAL_CORE_DB" || $name == "$REAL_PAYMENT_DB" ]]; then echo "실제 DB 이름과 같아 만들지 않습니다: $name" >&2; return 1; fi
}
create_temp_db() { # 이름 [소유자]
  local name="$1" owner="${2:-}"
  assert_temp_name "$name" || return 1
  if [[ -n $owner ]]; then admin_sql postgres "CREATE DATABASE \"$name\" OWNER \"$owner\"" >/dev/null || return 1
  else admin_sql postgres "CREATE DATABASE \"$name\"" >/dev/null || return 1; fi
  TRACKED+=("$name") # CREATE가 성공한 뒤에만 기록한다
}
drop_tracked() {
  local rc=0 db remaining=()
  for db in "${TRACKED[@]+"${TRACKED[@]}"}"; do
    if admin_sql postgres "DROP DATABASE \"$db\" WITH (FORCE)" >/dev/null 2>&1; then :
    else echo "임시 DB 정리 실패: $db" >&2; remaining+=("$db"); rc=1; fi
  done
  TRACKED=("${remaining[@]+"${remaining[@]}"}")
  return $rc
}
mark_template() { # 이름, true|false
  admin_sql postgres "ALTER DATABASE \"$1\" IS_TEMPLATE $2" >/dev/null || return 1
  if [[ $2 == true ]]; then TEMPLATE_MARKED+=("$1"); fi
}
unmark_templates() {
  local db
  for db in "${TEMPLATE_MARKED[@]+"${TEMPLATE_MARKED[@]}"}"; do
    admin_sql postgres "ALTER DATABASE \"$db\" IS_TEMPLATE false" >/dev/null 2>&1 || true
  done
  TEMPLATE_MARKED=()
}
cleanup() {
  local rc=$?
  unmark_templates
  drop_tracked || rc=1
  docker compose exec -T postgres rm -f "$FAKE_DUMP_FAIL" "$FAKE_DUMP_TRUNC" "$FAKE_PSQL_LOST" "$FAKE_PSQL_LOST_DIFF" "$FAKE_PSQL_NOLOAD" "$FAKE_PSQL_SLOW" >/dev/null 2>&1 || true
  rm -rf "$LOG" "$FLAG_DIR"
  if [[ ${#TRACKED[@]} -gt 0 ]]; then echo "임시 DB 정리 확인: 남은 임시 DB ${#TRACKED[@]}개(${TRACKED[*]})" >&2; rc=1
  else echo '임시 DB 정리 확인: 이 실행이 만든 임시 DB 남은 것 0개'; fi
  exit $rc
}
trap cleanup EXIT

# 스키마 복제. 파이프 어느 단계가 실패해도 즉시 중단한다. 인자: 원본 DB, 대상 DB
clone_schema() {
  docker compose exec -T -e CLONE_FROM="$1" -e CLONE_TO="$2" postgres bash -c '
    set -euo pipefail
    pg_dump -U "$POSTGRES_USER" -d "$CLONE_FROM" --schema-only -n payment -n public \
      | sed "/^CREATE SCHEMA public;/d" \
      | psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$CLONE_TO" -o /dev/null'
}
new_source() { create_temp_db "$1" && clone_schema "$REAL_CORE_DB" "$1"; }
new_target() { create_temp_db "$1" "$PAYMENT_USER" && clone_schema "$REAL_PAYMENT_DB" "$1"; }

if [[ $COLLISION_PROBE == true ]]; then
  # 접미사가 같은 DB가 이미 있으면 CREATE가 실패해야 하고, 그 DB는 정리 대상이 아니어야 한다
  create_temp_db "tmp_mig_src_$SUFFIX"
  exit 0
fi

if [[ $FAIL_PROBE == true ]]; then
  # 소스·대상·템플릿 표시 DB를 만든 뒤 일부러 중단한다. EXIT 정리가 모두 지워야 한다
  new_source "tmp_mig_src_$SUFFIX"
  new_target "tmp_mig_dst_$SUFFIX"
  create_temp_db "tmp_mig_cleanup_$SUFFIX"
  mark_template "tmp_mig_cleanup_$SUFFIX" true # 템플릿 표시까지 끝낸 뒤에 의도한 실패에 도달한다
  echo '의도한 중간 실패' >&2
  exit 1
fi

pass=0
check() { # 설명, 기대 종료 코드(숫자 또는 nonzero), 로그에서 찾을 문구(선택), 명령...
  local desc="$1" want="$2" pattern="$3"; shift 3
  local got=0
  "$@" >"$LOG" 2>&1 || got=$?
  if [[ "$want" == nonzero ]]; then [[ $got -ne 0 ]] || { echo "FAIL: $desc (종료 코드 0, 실패를 기대)"; cat "$LOG"; exit 1; }
  else [[ $got -eq "$want" ]] || { echo "FAIL: $desc (종료 코드 $got)"; cat "$LOG"; exit 1; }; fi
  if [[ -n "$pattern" ]] && ! grep -q -- "$pattern" "$LOG"; then
    echo "FAIL: $desc (출력에 '$pattern' 없음)"; cat "$LOG"; exit 1
  fi
  echo "PASS: $desc (종료 코드 $got)"
  pass=$((pass + 1))
}
assert_eq() { # 설명, 기대값, 실제값
  [[ "$2" == "$3" ]] || { echo "FAIL: $1 (기대 '$2', 실제 '$3')"; exit 1; }
  echo "PASS: $1 ($3)"; pass=$((pass + 1))
}

echo '== 경로 0: 임시 DB 보호 (R-004) =='
# (a) 실제 DB 이름이나 규칙에 맞지 않는 이름은 만들지 않는다
if create_temp_db "$REAL_CORE_DB" 2>/dev/null || create_temp_db "$REAL_PAYMENT_DB" 2>/dev/null || create_temp_db 'tmp_mig_x"; DROP DATABASE postgres; --' 2>/dev/null; then
  echo 'FAIL: 실제 DB 이름 또는 규칙 밖 이름으로 DB를 만들었다'; exit 1
fi
echo 'PASS: 실제 DB 이름·규칙 밖 이름은 만들지 않는다'; pass=$((pass + 1))
# (b) 같은 이름의 DB가 이미 있으면 만들지 못하고, 정리 대상에도 들어가지 않으며, 정리 뒤에도 남는다
GUARD_SUFFIX="g$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
FOREIGN="tmp_mig_src_$GUARD_SUFFIX"
create_temp_db "$FOREIGN" # 이 부모가 만든 DB이므로 부모의 정리 목록에 넣는다(이후 검사가 실패해도 EXIT 정리가 지운다)
admin_sql "$FOREIGN" 'CREATE TABLE keep_me AS SELECT 1 AS x' >/dev/null
if create_temp_db "$FOREIGN" 2>/dev/null; then echo 'FAIL: 이미 있는 DB를 만든 것으로 처리했다'; exit 1; fi
assert_eq '이미 있는 이름의 두 번째 생성은 추적 목록을 늘리지 않는다' 1 "${#TRACKED[@]}"
check '이름 충돌 시 자식 실행이 실패한다' nonzero '' env TEST_DB_SUFFIX="$GUARD_SUFFIX" "$0" --collision-probe
assert_eq '자식 실행이 부모가 만든 충돌 DB와 데이터를 지우지 않는다' 1 "$(admin_sql "$FOREIGN" 'SELECT count(*) FROM keep_me')"
drop_tracked
assert_eq '부모가 충돌 검사 DB를 정리해 목록이 빈다' 0 "${#TRACKED[@]}"
# (c) 정리 실패는 반영된다(템플릿 DB는 DROP할 수 없다)
create_temp_db "tmp_mig_cleanup_$SUFFIX"
mark_template "tmp_mig_cleanup_$SUFFIX" true
if drop_tracked 2>/dev/null; then echo 'FAIL: 정리 실패가 반영되지 않았다'; exit 1; fi
assert_eq '정리에 실패하면 drop_tracked가 실패로 알리고 목록에 남긴다' 1 "${#TRACKED[@]}"
unmark_templates
drop_tracked
assert_eq '복구 뒤 정리하면 목록이 빈다' 0 "${#TRACKED[@]}"
# (d) 검사 중간에 실패해도(템플릿 표시 상태 포함) 그 실행이 만든 임시 DB가 남지 않는다
PROBE_SUFFIX="p$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
check '중간 실패하는 자식 실행은 템플릿 표시 뒤 의도한 실패에 도달해 비정상 종료한다' nonzero '의도한 중간 실패' env TEST_DB_SUFFIX="$PROBE_SUFFIX" "$0" --fail-probe
grep -q '남은 것 0개' "$LOG" || { echo 'FAIL: 자식 실행의 정리 확인 출력이 없다'; cat "$LOG"; exit 1; }
echo 'PASS: 자식 실행이 정리 확인(남은 것 0개)까지 출력했다'; pass=$((pass + 1))
assert_eq '중간 실패한 실행의 임시 DB가 하나도 남지 않는다' 0 "$(admin_sql postgres "SELECT count(*) FROM pg_database WHERE datname LIKE 'tmp\\_mig\\_%\\_$PROBE_SUFFIX'")"

echo
echo "== 임시 DB 준비 (접미사 $SUFFIX) =="
SRC="tmp_mig_src_$SUFFIX"
DST="tmp_mig_dst_$SUFFIX"
new_source "$SRC"
new_target "$DST"

# 덤프 실패 시뮬레이션 훅(MIGRATE_PG_DUMP). 둘 다 첫 COPY 블록이 끝난 뒤 출력을 멈춘다.
# fail은 종료 코드 1, trunc는 종료 코드 0(조용히 잘린 덤프)이다. trunc는 \restrict를 빼서 이후 psql 메타 명령이 막히지 않게 한다.
docker compose exec -T postgres sh -c "cat > '$FAKE_DUMP_FAIL' && chmod +x '$FAKE_DUMP_FAIL'" <<'EOF'
#!/bin/sh
pg_dump "$@" | awk '{print} /^\\\.$/ {exit}'
exit 1
EOF
docker compose exec -T postgres sh -c "cat > '$FAKE_DUMP_TRUNC' && chmod +x '$FAKE_DUMP_TRUNC'" <<'EOF'
#!/bin/sh
pg_dump "$@" | awk '/^\\restrict/ {next} {print} /^\\\.$/ {exit}'
exit 0
EOF

# 적재 psql 시뮬레이션 훅(MIGRATE_LOAD_PSQL). 서버는 커밋했는데 클라이언트가 응답을 잃은 경우와 적재 전에 죽은 경우를 만든다.
#   lost: 진짜 psql로 적재(COMMIT 완료)한 뒤 종료 코드 2로 끝난다
#   lostdiff: 적재 후 대상 한 행을 바꾸고(다른 세션의 쓰기) 종료 코드 2로 끝난다
#   noload: 아무것도 하지 않고 종료 코드 2로 끝난다
#   slow: 적재 SQL의 COMMIT 직전에 6초 지연을 넣어 백그라운드로 실행하고, 1초 뒤 적재가 끝나기 전에 종료 코드 2로 끝난다(적재 트랜잭션 진행 중 실패)
docker compose exec -T postgres sh -c "cat > '$FAKE_PSQL_LOST' && chmod +x '$FAKE_PSQL_LOST'" <<'EOF'
#!/bin/sh
psql "$@"
exit 2
EOF
docker compose exec -T postgres sh -c "cat > '$FAKE_PSQL_LOST_DIFF' && chmod +x '$FAKE_PSQL_LOST_DIFF'" <<'EOF'
#!/bin/sh
psql "$@"
PGPASSWORD="$TICKETING_PAYMENT_POSTGRES_PASSWORD" psql -X --no-password -q -U "$TICKETING_PAYMENT_POSTGRES_USER" -d "$MIGRATE_TARGET_DB" -c "UPDATE payment.payment SET amount = amount + 1 WHERE id = 101"
exit 2
EOF
docker compose exec -T postgres sh -c "cat > '$FAKE_PSQL_NOLOAD' && chmod +x '$FAKE_PSQL_NOLOAD'" <<'EOF'
#!/bin/sh
exit 2
EOF

docker compose exec -T postgres sh -c "cat > '$FAKE_PSQL_SLOW' && chmod +x '$FAKE_PSQL_SLOW'" <<'EOF'
#!/bin/bash
args=("$@")
f="${args[-1]}"
sed 's/^COMMIT;$/SELECT pg_sleep(6);\nCOMMIT;/' "$f" > "$f.slow"
args[-1]="$f.slow"
psql "${args[@]}" &
sleep 1
exit 2
EOF

# 원본 샘플: 모든 상태, NULL 열, 큰 시퀀스 값, 한글·특수 문자를 포함한다
admin_sql "$SRC" "
INSERT INTO payment.payment (id, order_id, member_id, amount, status, idempotency_key, pg_transaction_id, failure_reason, requested_at, approved_at, failed_at, refund_requested_at, refunded_at, version) VALUES
 (101, 1, 10, 50000, 'SUCCEEDED', 'k-1', 'txn-1', NULL, now(), now(), NULL, NULL, NULL, 1),
 (102, 2, 10, 70000, 'FAILED', 'k-2', NULL, '한도 초과|특수\\문자', now(), NULL, now(), NULL, NULL, 1),
 (103, 3, 11, 30000, 'REFUNDED', 'k-3', 'txn-3', NULL, now(), now(), NULL, now(), now(), 3),
 (104, 4, 12, 90000, 'REQUESTED', 'k-4', NULL, NULL, now(), NULL, NULL, NULL, NULL, 0),
 (105, 5, 12, 10000, 'REFUND_REQUESTED', 'k-5', 'txn-5', NULL, now(), now(), NULL, now(), NULL, 2);
SELECT setval('payment.payment_id_seq', 500, true);
INSERT INTO payment.webhook_event VALUES ('evt-1','PAYMENT_APPROVED','txn-1',now()), ('evt-2','REFUND_COMPLETED','txn-3',now());
INSERT INTO payment.pg_transaction_snapshot VALUES
 ('2026-11-20','txn-1',1,50000,'APPROVED',now(),now(),NULL,now()), ('2026-11-20','txn-3',3,30000,'REFUNDED',now(),now(),now(),now());
INSERT INTO payment.reconciliation_mismatch VALUES
 ('2026-11-20',9,'MISSING_INTERNAL','txn-9','APPROVED',12000,NULL,NULL,NULL,now()),
 ('2026-11-20',3,'AMOUNT_MISMATCH','txn-3','REFUNDED',30000,103,'REFUNDED',31000,now())" >/dev/null

echo
echo '== 경로 1: 성공 (건수·체크섬 일치, 시퀀스 다음 값) =='
check '임시 원본 -> 임시 대상 이전' 0 '대조 일치' "$MIGRATE" --source-db "$SRC" --target-db "$DST"
cat "$LOG"
assert_eq '대상 다음 ID = max(최대 id+1=106, 원본 다음 값 501)' 501 "$(admin_sql "$DST" "SELECT nextval('payment.payment_id_seq')")"
check '--verify-only 일치' 0 '대조 일치' "$MIGRATE" --verify-only --source-db "$SRC" --target-db "$DST"

echo
echo '== 경로 2: 대상이 비어 있지 않으면 중단 =='
check '이미 이전된 대상에 다시 이전 시도' nonzero '덮어쓰지 않습니다' "$MIGRATE" --source-db "$SRC" --target-db "$DST"
cat "$LOG"
assert_eq '중단 후에도 대상 payment 건수 그대로' 5 "$(payment_sql "$DST" 'SELECT count(*) FROM payment.payment')"

echo
echo '== 경로 3: 미완료 event_publication이면 중단 =='
DST2="tmp_mig_dst2_$SUFFIX"
new_target "$DST2"
EVENT_SQL="INSERT INTO public.event_publication (id, listener_id, event_type, serialized_event, publication_date) VALUES (gen_random_uuid(), 'l', 't', '{}', now())"
admin_sql "$SRC" "$EVENT_SQL" >/dev/null
check '원본에 미완료 이벤트' nonzero '미완료 event_publication' "$MIGRATE" --source-db "$SRC" --target-db "$DST2"
cat "$LOG"
admin_sql "$SRC" "DELETE FROM public.event_publication" >/dev/null
admin_sql "$DST2" "$EVENT_SQL" >/dev/null
check '대상에 미완료 이벤트' nonzero '미완료 event_publication' "$MIGRATE" --source-db "$SRC" --target-db "$DST2"
cat "$LOG"
assert_eq '중단 후 대상 payment 0건' 0 "$(admin_sql "$DST2" 'SELECT count(*) FROM payment.payment')"
admin_sql "$DST2" "DELETE FROM public.event_publication" >/dev/null

echo
echo '== 경로 4: 체크섬 불일치 검출 =='
admin_sql "$DST" "UPDATE payment.payment SET status = 'FAILED' WHERE id = 101" >/dev/null
check '대상 payment 한 행 상태 변경 후 --verify-only' nonzero '다릅니다' "$MIGRATE" --verify-only --source-db "$SRC" --target-db "$DST"
cat "$LOG"
admin_sql "$DST" "UPDATE payment.payment SET status = 'SUCCEEDED' WHERE id = 101" >/dev/null
admin_sql "$DST" "UPDATE payment.reconciliation_mismatch SET internal_amount = 1 WHERE order_id = 3" >/dev/null
check '대상 reconciliation_mismatch 금액 변경 후 --verify-only' nonzero '다릅니다' "$MIGRATE" --verify-only --source-db "$SRC" --target-db "$DST"
admin_sql "$DST" "UPDATE payment.reconciliation_mismatch SET internal_amount = 31000 WHERE order_id = 3" >/dev/null
check '복구 후 --verify-only 다시 일치' 0 '대조 일치' "$MIGRATE" --verify-only --source-db "$SRC" --target-db "$DST"

echo
echo '== R-001: 덤프 실패·잘린 덤프는 부분 커밋되지 않는다 =='
# DST2는 비어 있다. 시퀀스 초기 상태를 기록해 두었다가 롤백 뒤 같은지 본다
SEQ_QUERY="SELECT last_value || '|' || is_called FROM payment.payment_id_seq"
SEQ_BEFORE="$(admin_sql "$DST2" "$SEQ_QUERY")"
check '첫 COPY가 끝난 뒤 pg_dump가 실패(종료 코드 1)' nonzero 'pg_dump가 실패했습니다' env MIGRATE_PG_DUMP="$FAKE_DUMP_FAIL" "$MIGRATE" --source-db "$SRC" --target-db "$DST2"
cat "$LOG"
assert_eq '덤프 실패 뒤 대상 payment 0건' 0 "$(admin_sql "$DST2" 'SELECT count(*) FROM payment.payment')"
check '첫 COPY까지만 있는 잘린 덤프(종료 코드 0)는 커밋 전 대조에서 롤백' nonzero '커밋 전 대조 실패' env MIGRATE_PG_DUMP="$FAKE_DUMP_TRUNC" "$MIGRATE" --source-db "$SRC" --target-db "$DST2"
cat "$LOG"
assert_eq '잘린 덤프 롤백 뒤 대상 payment 0건(첫 COPY의 5행이 남지 않는다)' 0 "$(admin_sql "$DST2" 'SELECT count(*) FROM payment.payment')"
assert_eq '롤백 뒤 나머지 3테이블도 0건' 0 "$(admin_sql "$DST2" 'SELECT (SELECT count(*) FROM payment.webhook_event) + (SELECT count(*) FROM payment.pg_transaction_snapshot) + (SELECT count(*) FROM payment.reconciliation_mismatch)')"
echo '== R-003(b): ALTER SEQUENCE ... RESTART 뒤 트랜잭션이 실패해도 시퀀스는 원상태 =='
assert_eq '롤백 뒤 대상 시퀀스(last_value|is_called)가 이전과 같다' "$SEQ_BEFORE" "$(admin_sql "$DST2" "$SEQ_QUERY")"
check '정상 덤프로 다시 실행하면 성공(즉시 재시도 가능)' 0 '대조 일치' "$MIGRATE" --source-db "$SRC" --target-db "$DST2"

echo
echo '== R-001: 커밋 직전 SQL 실패와 커밋 응답 유실을 구분해 안내한다 =='
DST_C="tmp_mig_commit_$SUFFIX"
new_target "$DST_C"
check '커밋 전 대조 실패(잘린 덤프)는 적재 종료 뒤 대상이 그대로임을 확인한다' nonzero '반영된 변경이 없음을 확인' env MIGRATE_PG_DUMP="$FAKE_DUMP_TRUNC" "$MIGRATE" --source-db "$SRC" --target-db "$DST_C"
cat "$LOG"
check '적재 전에 psql이 죽으면 반영된 변경이 없음을 확인한다' nonzero '반영된 변경이 없음을 확인' env MIGRATE_LOAD_PSQL="$FAKE_PSQL_NOLOAD" "$MIGRATE" --source-db "$SRC" --target-db "$DST_C"
assert_eq '적재 전 실패 뒤 대상 payment 0건' 0 "$(admin_sql "$DST_C" 'SELECT count(*) FROM payment.payment')"
check '서버는 커밋했고 응답만 잃은 경우: 롤백됐다고 안내하지 않고 일치 여부를 알려 준다' nonzero '커밋 응답만 잃은 것으로 보입니다' env MIGRATE_LOAD_PSQL="$FAKE_PSQL_LOST" "$MIGRATE" --source-db "$SRC" --target-db "$DST_C"
cat "$LOG"
if grep -q '아무것도 반영되지 않았' "$LOG"; then echo 'FAIL: 커밋된 상태인데 롤백됐다고 안내했다'; exit 1; fi
echo 'PASS: 커밋된 상태를 롤백됐다고 안내하지 않는다'; pass=$((pass + 1))
assert_eq '응답 유실 뒤 대상에는 이전 데이터가 커밋돼 있다' 5 "$(admin_sql "$DST_C" 'SELECT count(*) FROM payment.payment')"
check '응답 유실 뒤 --verify-only로 재대조하면 일치' 0 '대조 일치' "$MIGRATE" --verify-only --source-db "$SRC" --target-db "$DST_C"
DST_D="tmp_mig_commitdiff_$SUFFIX"
new_target "$DST_D"
check '커밋 응답 유실 + 대상이 원본과 다른 경우: 다시 실행하지 말고 확인하라고 안내' nonzero '원본 스냅샷과 다릅니다' env MIGRATE_LOAD_PSQL="$FAKE_PSQL_LOST_DIFF" "$MIGRATE" --source-db "$SRC" --target-db "$DST_D"
cat "$LOG"

echo
echo '== R-003(a): is_called=false 이고 last_value = max(id) 인 원본 =='
SRC_SEQ="tmp_mig_srcseq_$SUFFIX"; DST_SEQ="tmp_mig_dstseq_$SUFFIX"
new_source "$SRC_SEQ"; new_target "$DST_SEQ"
admin_sql "$SRC_SEQ" "INSERT INTO payment.payment (id, order_id, member_id, amount, status, idempotency_key, requested_at) VALUES (1, 1, 1, 1000, 'SUCCEEDED', 'seq-1', now())" >/dev/null
assert_eq '원본 시퀀스 상태 last_value|is_called' '1|false' "$(admin_sql "$SRC_SEQ" "$SEQ_QUERY")"
check 'is_called=false 원본 이전' 0 '대조 일치' "$MIGRATE" --source-db "$SRC_SEQ" --target-db "$DST_SEQ"
assert_eq '대상에서 id 없이 삽입하면 2가 발급되고 PK 충돌이 없다' 2 "$(payment_sql "$DST_SEQ" "INSERT INTO payment.payment (order_id, member_id, amount, status, idempotency_key, requested_at) VALUES (2, 1, 1000, 'SUCCEEDED', 'seq-2', now()) RETURNING id")"

echo
echo '== R-002: 적재 트랜잭션 안의 잠금·재검사 =='
# 커밋되지 않은 쓰기를 쥔 세션(holder)을 열어 두면, 사전 검사는 통과하지만 적재 트랜잭션은 잠금에서 기다린다.
# holder가 커밋한 뒤 잠금을 얻은 트랜잭션이 재검사에서 그 쓰기를 발견해 중단해야 한다.
wait_for() { # 설명, DB, SQL(결과가 t면 통과), 제한 초(기본 40)
  local n=0 limit="${4:-40}"
  until [[ "$(admin_sql "$2" "$3" 2>/dev/null || true)" == t ]]; do
    n=$((n + 1)); [[ $n -le $((limit * 5)) ]] || { echo "FAIL: 시간 초과: $1"; exit 1; }
    sleep 0.2
  done
}
# 인자: DB, 해제 플래그 파일, holder가 트랜잭션 안에서 실행할 SQL. 백그라운드로 실행하고 HOLDER_PID를 설정한다
start_holder() {
  local db="$1" flag="$2" sql="$3"
  ( echo "BEGIN; $sql"; while [[ ! -f "$flag" ]]; do sleep 0.2; done; echo 'COMMIT;' ) \
    | docker compose exec -T -e TICKETING_PAYMENT_POSTGRES_USER -e TICKETING_PAYMENT_POSTGRES_PASSWORD -e HOLD_DB="$db" postgres sh -c \
      'PGPASSWORD="$TICKETING_PAYMENT_POSTGRES_PASSWORD" exec psql -X --no-password -q -v ON_ERROR_STOP=1 -U "$TICKETING_PAYMENT_POSTGRES_USER" -d "$HOLD_DB" -o /dev/null' >/dev/null 2>&1 &
  HOLDER_PID=$!
}

# 대상 DB의 기본 격리 수준(READ COMMITTED 또는 REPEATABLE READ)별로 (a) 경합 쓰기, (b) 경합 이벤트를 검사한다.
# REPEATABLE READ가 기본이면 BEGIN 직후 첫 SELECT가 스냅샷을 확정해 잠금 뒤 재검사가 경합 쓰기를 놓칠 수 있다(적재는 READ COMMITTED를 명시해야 한다)
race_tests() { # 태그, 기본 격리 수준
  local tag="$1" iso="$2" db1="tmp_mig_race${1}_$SUFFIX" db2="tmp_mig_racev${1}_$SUFFIX"
  new_target "$db1"; new_target "$db2"
  if [[ $iso == "repeatable read" ]]; then
    admin_sql postgres "ALTER DATABASE \"$db1\" SET default_transaction_isolation TO 'repeatable read'" >/dev/null
    admin_sql postgres "ALTER DATABASE \"$db2\" SET default_transaction_isolation TO 'repeatable read'" >/dev/null
  fi
  assert_eq "[$tag] 대상 DB 기본 격리 수준" "$iso" "$(payment_sql "$db1" 'SHOW default_transaction_isolation')"

  # (a) 경합 쓰기: 사전 검사 때 비어 있던 대상에 holder가 행을 커밋한다
  start_holder "$db1" "$FLAG_DIR/$tag-a" "INSERT INTO payment.payment (order_id, member_id, amount, status, idempotency_key, requested_at) VALUES (900, 1, 1000, 'SUCCEEDED', 'race-1', now());"
  wait_for 'holder 열림' "$db1" "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = '$db1' AND state = 'idle in transaction')"
  ( rc=0; "$MIGRATE" --source-db "$SRC" --target-db "$db1" >"$FLAG_DIR/$tag-a.log" 2>&1 || rc=$?; echo $rc >"$FLAG_DIR/$tag-a.rc" ) &
  MIGRATE_PID=$!
  wait_for "[$tag] 적재 트랜잭션이 LOCK TABLE에서 대기" "$db1" "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = '$db1' AND wait_event_type = 'Lock' AND query LIKE 'LOCK TABLE payment.payment%')"
  echo "PASS: [$tag] 사전 검사를 통과한 이전이 쓰기 차단 잠금에서 대기 중(holder가 행을 쥐고 있음)"; pass=$((pass + 1))
  touch "$FLAG_DIR/$tag-a"; wait "$HOLDER_PID" || true; wait "$MIGRATE_PID" || true
  cat "$FLAG_DIR/$tag-a.log"
  assert_eq "[$tag] 재검사가 경합 쓰기를 발견해 중단(종료 코드 1)" 1 "$(cat "$FLAG_DIR/$tag-a.rc")"
  grep -q '적재 직전 재검사: 대상 테이블에 데이터가 있습니다' "$FLAG_DIR/$tag-a.log" || { echo "FAIL: [$tag] 재검사 메시지 없음"; exit 1; }
  echo "PASS: [$tag] 메시지에 적재 직전 재검사 사유가 있다"; pass=$((pass + 1))
  assert_eq "[$tag] 대상에는 holder의 1행만 남고 이전 데이터는 반영되지 않았다" 1 "$(admin_sql "$db1" 'SELECT count(*) FROM payment.payment')"
  assert_eq "[$tag] 남은 1행이 holder가 넣은 행이다" race-1 "$(admin_sql "$db1" 'SELECT idempotency_key FROM payment.payment')"

  # (b) 경합 이벤트: 사전 검사 뒤에 대상 event_publication에 미완료 행이 커밋된다
  start_holder "$db2" "$FLAG_DIR/$tag-b" "INSERT INTO public.event_publication (id, listener_id, event_type, serialized_event, publication_date) VALUES (gen_random_uuid(), 'l', 't', '{}', now());"
  wait_for 'holder 열림' "$db2" "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = '$db2' AND state = 'idle in transaction')"
  ( rc=0; "$MIGRATE" --source-db "$SRC" --target-db "$db2" >"$FLAG_DIR/$tag-b.log" 2>&1 || rc=$?; echo $rc >"$FLAG_DIR/$tag-b.rc" ) &
  MIGRATE_PID=$!
  wait_for "[$tag] 적재 트랜잭션이 event_publication LOCK에서 대기" "$db2" "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = '$db2' AND wait_event_type = 'Lock' AND query LIKE 'LOCK TABLE public.event_publication%')"
  echo "PASS: [$tag] 사전 검사를 통과한 이전이 event_publication 잠금에서 대기 중"; pass=$((pass + 1))
  touch "$FLAG_DIR/$tag-b"; wait "$HOLDER_PID" || true; wait "$MIGRATE_PID" || true
  cat "$FLAG_DIR/$tag-b.log"
  assert_eq "[$tag] 재검사가 미완료 이벤트를 발견해 중단(종료 코드 1)" 1 "$(cat "$FLAG_DIR/$tag-b.rc")"
  grep -q '적재 직전 재검사: 대상에 미완료 event_publication이 1건' "$FLAG_DIR/$tag-b.log" || { echo "FAIL: [$tag] 이벤트 재검사 메시지 없음"; exit 1; }
  echo "PASS: [$tag] 메시지에 미완료 이벤트 재검사 사유가 있다"; pass=$((pass + 1))
  assert_eq "[$tag] 대상 payment는 비어 있다(적재가 롤백됨)" 0 "$(admin_sql "$db2" 'SELECT count(*) FROM payment.payment')"
  assert_eq "[$tag] 대상 event_publication에는 holder의 1행만 있다" 1 "$(admin_sql "$db2" 'SELECT count(*) FROM public.event_publication')"
}
race_tests rc 'read committed'
race_tests rr 'repeatable read'

# (c) 같은 대상에 대한 실행 직렬화: advisory lock을 쥔 세션이 있으면 기다렸다가 풀리면 진행한다
DST_LOCK="tmp_mig_lock_$SUFFIX"
new_target "$DST_LOCK"
( echo "SELECT pg_advisory_lock(hashtextextended('ticketing.migrate-payment-data:' || current_database(), 0));"; while [[ ! -f "$FLAG_DIR/c" ]]; do sleep 0.2; done ) \
  | docker compose exec -T -e HOLD_DB="$DST_LOCK" postgres sh -c 'exec psql -X --no-password -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$HOLD_DB" -o /dev/null' >/dev/null 2>&1 &
HOLDER_PID=$!
wait_for 'advisory lock holder' "$DST_LOCK" "SELECT EXISTS (SELECT 1 FROM pg_locks l JOIN pg_stat_activity a USING (pid) WHERE l.locktype = 'advisory' AND l.granted AND a.datname = '$DST_LOCK')"
( rc=0; "$MIGRATE" --source-db "$SRC" --target-db "$DST_LOCK" >"$FLAG_DIR/c.log" 2>&1 || rc=$?; echo $rc >"$FLAG_DIR/c.rc" ) &
MIGRATE_PID=$!
wait_for '다른 실행이 advisory lock에서 대기' "$DST_LOCK" "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = '$DST_LOCK' AND wait_event_type = 'Lock' AND query LIKE 'SELECT pg_advisory_xact_lock%')"
echo 'PASS: 같은 대상을 향한 실행은 advisory lock에서 직렬화된다'; pass=$((pass + 1))
touch "$FLAG_DIR/c"; wait "$HOLDER_PID" || true; wait "$MIGRATE_PID" || true
assert_eq 'lock이 풀린 뒤 이전이 성공(종료 코드 0)' 0 "$(cat "$FLAG_DIR/c.rc")"
assert_eq '직렬화된 실행이 데이터를 적재' 5 "$(admin_sql "$DST_LOCK" 'SELECT count(*) FROM payment.payment')"

echo
echo '== R-001: 커밋 여부 확인은 적재 트랜잭션이 끝난 뒤의 상태를 읽는다 =='
# (a) 원본 4테이블이 비어 있고 원본 시퀀스만 앞서 있는 경우: 적재는 대상 시퀀스만 바꿔 커밋한다. 응답을 잃어도 0건만 보고 롤백을 단정하면 안 된다
SRC_EMPTY="tmp_mig_srcempty_$SUFFIX"; DST_EMPTY="tmp_mig_dstempty_$SUFFIX"
new_source "$SRC_EMPTY"; new_target "$DST_EMPTY"
admin_sql "$SRC_EMPTY" "SELECT setval('payment.payment_id_seq', 100, true)" >/dev/null
check '빈 원본 + 시퀀스 재시작 커밋 뒤 응답 유실: 시퀀스만 반영된 커밋으로 안내' nonzero '시퀀스만 반영하는 적재가 커밋되고 응답만 잃은 것으로 보입니다' env MIGRATE_LOAD_PSQL="$FAKE_PSQL_LOST" "$MIGRATE" --source-db "$SRC_EMPTY" --target-db "$DST_EMPTY"
cat "$LOG"
if grep -q '반영된 변경이 없음' "$LOG"; then echo 'FAIL: 시퀀스가 커밋됐는데 변경이 없다고 안내했다'; exit 1; fi
echo 'PASS: 시퀀스가 커밋된 상태를 변경 없음으로 안내하지 않는다'; pass=$((pass + 1))
assert_eq '대상 4테이블은 비어 있다' 0 "$(admin_sql "$DST_EMPTY" 'SELECT (SELECT count(*) FROM payment.payment) + (SELECT count(*) FROM payment.webhook_event) + (SELECT count(*) FROM payment.pg_transaction_snapshot) + (SELECT count(*) FROM payment.reconciliation_mismatch)')"
assert_eq '대상 시퀀스는 커밋돼 있다(last_value|is_called)' '101|false' "$(admin_sql "$DST_EMPTY" "$SEQ_QUERY")"
check '빈 원본 응답 유실 뒤 --verify-only 재대조가 일치' 0 '대조 일치' "$MIGRATE" --verify-only --source-db "$SRC_EMPTY" --target-db "$DST_EMPTY"

# (b) 적재 트랜잭션이 아직 끝나지 않았는데 클라이언트가 실패한 경우: COMMIT 직전 지연 중에 확인 조회가 시작된다.
# 종료를 기다리지 않고 읽으면 0건으로 보여 롤백으로 오판한다. 잠금을 얻어 종료를 기다리면 커밋된 5건과 일치로 확인한다
DST_SLOW="tmp_mig_slow_$SUFFIX"
new_target "$DST_SLOW"
check '적재 트랜잭션 종료 전 확인 조회: 종료를 기다려 커밋된 결과를 안내' nonzero '커밋 응답만 잃은 것으로 보입니다' env MIGRATE_LOAD_PSQL="$FAKE_PSQL_SLOW" "$MIGRATE" --source-db "$SRC" --target-db "$DST_SLOW"
cat "$LOG"
if grep -q '반영된 변경이 없음' "$LOG"; then echo 'FAIL: 아직 끝나지 않은 적재를 변경 없음으로 안내했다'; exit 1; fi
echo 'PASS: 진행 중이던 적재를 변경 없음으로 오판하지 않는다'; pass=$((pass + 1))
assert_eq '커밋된 5건이 대상에 있다' 5 "$(admin_sql "$DST_SLOW" 'SELECT count(*) FROM payment.payment')"

# (c) 종료를 확인할 수 없는 경우(같은 advisory lock을 다른 세션이 쥐고 있어 시간 안에 얻지 못함): 결과 미확인으로 안내한다
DST_TO="tmp_mig_lockto_$SUFFIX"
new_target "$DST_TO"
( echo "SELECT pg_advisory_lock(hashtextextended('ticketing.migrate-payment-data:' || current_database(), 0));"; while [[ ! -f "$FLAG_DIR/to" ]]; do sleep 0.2; done ) \
  | docker compose exec -T -e HOLD_DB="$DST_TO" postgres sh -c 'exec psql -X --no-password -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$HOLD_DB" -o /dev/null' >/dev/null 2>&1 &
HOLDER_PID=$!
wait_for 'advisory lock holder' "$DST_TO" "SELECT EXISTS (SELECT 1 FROM pg_locks l JOIN pg_stat_activity a USING (pid) WHERE l.locktype = 'advisory' AND l.granted AND a.datname = '$DST_TO')"
check '확인 조회가 잠금을 시간 안에 얻지 못하면 결과 미확인으로 안내' nonzero '적재 트랜잭션의 종료를 확인하지 못했습니다' env MIGRATE_LOAD_PSQL="$FAKE_PSQL_NOLOAD" MIGRATE_STATUS_LOCK_TIMEOUT=2s "$MIGRATE" --source-db "$SRC" --target-db "$DST_TO"
cat "$LOG"
if grep -q '반영된 변경이 없음' "$LOG"; then echo 'FAIL: 종료를 확인하지 못했는데 변경 없음으로 안내했다'; exit 1; fi
echo 'PASS: 종료 미확인을 변경 없음으로 안내하지 않는다'; pass=$((pass + 1))
touch "$FLAG_DIR/to"; wait "$HOLDER_PID" || true

if [[ "$FULL" == true ]]; then
  echo
  echo '== 대용량: 실제 core DB(읽기만) -> 임시 대상 =='
  DST_FULL="tmp_mig_full_$SUFFIX"
  new_target "$DST_FULL"
  start=$SECONDS
  check '실제 core DB -> 임시 대상 이전' 0 '대조 일치' "$MIGRATE" --target-db "$DST_FULL"
  cat "$LOG"
  echo "소요: $((SECONDS - start))초"
fi

if [[ "$FLYWAY_BOOT" == true ]]; then
  echo
  echo '== R-005: payment-batch.jar를 Job 없이 기동해 Flyway만 적용 =='
  JAR="$ROOT/payment-batch/build/libs/payment-batch.jar"
  [[ -f $JAR ]] || { echo "FAIL: $JAR 가 없습니다. ./gradlew :payment-batch:bootJar 를 먼저 실행하세요"; exit 1; }
  DST_BOOT="tmp_mig_boot_$SUFFIX"
  create_temp_db "$DST_BOOT" "$PAYMENT_USER"
  # 대상 DB만 임시 DB로 바꾼다. 소비 리스너 자동 기동 끔·미완료 이벤트 재발행 끔은 application-batch.yml의 batch 프로필 설정이다
  BOOT_LOG="$FLAG_DIR/boot.log"
  TICKETING_PAYMENT_POSTGRES_DB="$DST_BOOT" java -jar "$JAR" --spring.batch.job.enabled=false >"$BOOT_LOG" 2>&1 \
    || { echo 'FAIL: payment-batch 기동 실패'; rg -n -m 20 'ERROR|Exception' "$BOOT_LOG" || true; exit 1; }
  echo 'PASS: payment-batch가 Job 없이 기동했다가 종료 코드 0으로 종료'; pass=$((pass + 1))
  assert_eq 'Flyway 이력 3건(V1~V3)' 3 "$(admin_sql "$DST_BOOT" 'SELECT count(*) FROM public.flyway_schema_history WHERE success')"
  assert_eq 'payment 4테이블이 생성됨' 4 "$(admin_sql "$DST_BOOT" "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'payment'")"
  assert_eq 'payment 4테이블은 비어 있다' 0 "$(admin_sql "$DST_BOOT" 'SELECT (SELECT count(*) FROM payment.payment) + (SELECT count(*) FROM payment.webhook_event) + (SELECT count(*) FROM payment.pg_transaction_snapshot) + (SELECT count(*) FROM payment.reconciliation_mismatch)')"
  assert_eq 'event_publication도 비어 있다(재발행·발행 없음)' 0 "$(admin_sql "$DST_BOOT" 'SELECT count(*) FROM public.event_publication')"
  if rg -q 'partitions assigned|Kafka consumer|KafkaMessageListenerContainer' "$BOOT_LOG"; then echo 'FAIL: Kafka 컨슈머 기동 흔적이 있다'; rg -n -m 5 'partitions assigned|Kafka consumer|KafkaMessageListenerContainer' "$BOOT_LOG"; exit 1; fi
  echo 'PASS: 기동 로그에 Kafka 컨슈머 기동·파티션 할당(partitions assigned)이 없다'; pass=$((pass + 1))
fi

echo
echo "전체 통과: $pass개 확인"
