#!/usr/bin/env bash
# 실행 중인 서비스와 기존 회차에 UI 확인용 대기자를 추가한다.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
if [[ $# -lt 1 || $# -gt 2 || ! $1 =~ ^[1-9][0-9]*$ || ( $# == 2 && ! $2 =~ ^[1-9][0-9]*$ ) ]]; then
  echo '사용: ./scripts/loadtest/queue-ui.sh <회차 ID> [가상 사용자 수(기본 3000)]' >&2
  exit 1
fi
schedule_id=$1
vu_count=${2:-3000}
[[ -f .env ]] || { echo '.env 파일이 필요합니다.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Docker가 실행 중이어야 합니다.' >&2; exit 1; }
set -a
# shellcheck disable=SC1091
source .env
set +a
: "${TICKETING_JWT_SECRET:?TICKETING_JWT_SECRET이 필요합니다.}"
"$ROOT/scripts/loadtest/build-k6.sh"
"$ROOT/scripts/loadtest/seed.sh" members --count "$vu_count"
members_file=$(mktemp "$ROOT/build/loadtest/queue-ui-members.XXXXXX")
trap 'rm -f "$members_file"' EXIT
# 실제 ID 목록을 사용해 기존 회원 ID 사이의 빈 구간을 건너뛴다.
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qAt -v "count=$1"' sh "$vu_count" <<'SQL' > "$members_file"
SELECT coalesce(json_agg(id), '[]'::json) FROM (SELECT id FROM member.member WHERE role = 'USER' AND email ~ '^load-[0-9]+@loadtest[.]local$' ORDER BY id LIMIT :count) users;
SQL
# 이 스크립트가 사용하는 가상 회원의 이전 입장 상태만 정리한다.
docker compose exec -T redis redis-cli -x EVAL '
  local ids = cjson.decode(ARGV[2])
  for _, id in ipairs(ids) do
    local member = tostring(id)
    local admitted = "queue:admitted:{" .. ARGV[1] .. "}:" .. member
    local token = redis.call("GET", admitted)
    if token then redis.call("DEL", "queue:admission:" .. token, admitted) end
    redis.call("ZREM", KEYS[1], member)
    redis.call("ZREM", KEYS[2], member)
  end
  return #ids
' 2 "queue:waiting:{$schedule_id}" "queue:seen:{$schedule_id}" "$schedule_id" < "$members_file" > /dev/null
printf '회차 %s에 가상 대기자 %s명을 등록합니다. 대기 인원이 충분해지면 안내합니다.\n' "$schedule_id" "$vu_count"
(
  while :; do
    waiting=$(docker compose exec -T redis redis-cli ZCARD "queue:waiting:{$schedule_id}" 2>/dev/null | tr -d '\r') || exit
    if [[ $waiting =~ ^[0-9]+$ ]] && ((waiting >= 100)); then
      printf '\n현재 대기자 %s명: 브라우저에서 같은 회차에 진입하세요.\n' "$waiting"
      exit
    fi
    sleep 1
  done
) &
monitor_pid=$!
trap 'kill "$monitor_pid" 2>/dev/null || true; rm -f "$members_file"' EXIT
BASE_URL=${BASE_URL:-http://localhost:18000} SCHEDULE_ID="$schedule_id" VUS="$vu_count" MEMBERS_FILE="$members_file" \
  "$ROOT/build/loadtest/k6" run loadtest/scenarios/queue-ui.ts
