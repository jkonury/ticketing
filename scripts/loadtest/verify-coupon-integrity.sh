#!/usr/bin/env bash
# 사용: verify-coupon-integrity.sh <definitionId> [redis|db]  (측정 뒤 실행. 위반이 있으면 exit 1)
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
DEFINITION_ID=${1:-}
STRATEGY=${2:-redis}
[[ $DEFINITION_ID =~ ^[0-9]+$ && $STRATEGY =~ ^(redis|db)$ ]] || { echo '사용: verify-coupon-integrity.sh <definitionId> [redis|db]' >&2; exit 1; }
for command in docker jq; do
  command -v "$command" >/dev/null || { printf '필요한 도구가 없습니다: %s\n' "$command" >&2; exit 1; }
done
mkdir -p build/loadtest

# coupon 스키마만 조회한다
psql_q() {
  docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qAt -c "$1"' sh "$1"
}

total=$(psql_q "SELECT total_quantity FROM coupon.coupon_definition WHERE id = $DEFINITION_ID")
[[ $total =~ ^[0-9]+$ ]] || { printf '쿠폰 정의 %s를 찾지 못했습니다.\n' "$DEFINITION_ID" >&2; exit 1; }
issued=$(psql_q "SELECT count(*) FROM coupon.issued_coupon WHERE definition_id = $DEFINITION_ID")
duplicates=$(psql_q "SELECT count(*) FROM (SELECT 1 FROM coupon.issued_coupon WHERE definition_id = $DEFINITION_ID GROUP BY definition_id, member_id HAVING count(*) > 1) t")

violations=0
report() { # <이름> <위반 여부 0|1> <설명>
  local mark=OK
  if (($2)); then mark=FAIL; violations=$((violations + 1)); fi
  printf '[%s] %s: %s\n' "$mark" "$1" "$3"
}
report '초과 발급' $((issued > total)) "발급 ${issued} / 총수량 ${total}"
report '회원 중복 발급' $((duplicates != 0)) "(definition_id, member_id) 중복 ${duplicates}건"

stock=null
set_size=null
set_diff=null
if [[ $STRATEGY == redis ]]; then
  stock=$(docker compose exec -T redis redis-cli --raw GET "coupon:{$DEFINITION_ID}:stock" | tr -d '\r')
  if [[ $stock =~ ^-?[0-9]+$ ]]; then
    report '재고 합' $((stock + issued != total)) "Redis 재고 ${stock} + 발급 ${issued} = $((stock + issued)) / 총수량 ${total}"
  else
    stock=null
    report '재고 합' 1 'Redis 재고 키를 읽지 못했습니다'
  fi
  db_members=$(psql_q "SELECT member_id FROM coupon.issued_coupon WHERE definition_id = $DEFINITION_ID" | sort)
  redis_members=$(docker compose exec -T redis redis-cli --raw SMEMBERS "coupon:{$DEFINITION_ID}:claimed" | tr -d '\r' | grep . | sort || true)
  set_size=$(grep -c . <<<"$redis_members" || true)
  set_diff=$(comm -3 <(printf '%s\n' "$db_members") <(printf '%s\n' "$redis_members") | grep -c . || true)
  report 'Redis Set 일치' $((set_diff != 0)) "Redis claimed Set ${set_size} / DB 발급 ${issued}, 불일치 회원 ${set_diff}건"
else
  remaining=$(psql_q "SELECT remaining_quantity FROM coupon.coupon_definition WHERE id = $DEFINITION_ID")
  if [[ $remaining =~ ^-?[0-9]+$ ]]; then
    stock=$remaining
    report '재고 합' $((remaining + issued != total)) "DB 잔여 ${remaining} + 발급 ${issued} = $((remaining + issued)) / 총수량 ${total}"
  else
    report '재고 합' 1 'remaining_quantity를 읽지 못했습니다'
  fi
fi

jq -n --argjson definitionId "$DEFINITION_ID" --arg strategy "$STRATEGY" --argjson total "$total" --argjson issued "$issued" \
  --argjson duplicates "$duplicates" --argjson stock "$stock" --argjson setSize "$set_size" --argjson setDiff "$set_diff" \
  --argjson violations "$violations" \
  '{definitionId: $definitionId, strategy: $strategy, ok: ($violations == 0), violations: $violations,
    checks: {totalQuantity: $total, issuedCount: $issued, memberDuplicates: $duplicates,
             remainingStock: $stock, redisClaimedSetSize: $setSize, redisSetMismatch: $setDiff}}' > "build/loadtest/coupon-integrity-$DEFINITION_ID.json"
((violations == 0)) || { printf '정합성 위반 %s건. build/loadtest/coupon-integrity-%s.json 참고\n' "$violations" "$DEFINITION_ID" >&2; exit 1; }
