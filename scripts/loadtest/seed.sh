#!/usr/bin/env bash
# 사용: seed.sh members [--count 10000] | seed.sh schedule --scenario queue-surge|hot-seats|seat-conflict-refund
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
OUT="$ROOT/build/loadtest"
CORE_URL=${CORE_URL:-http://localhost:18080}
ORGANIZER_EMAIL='load-organizer@loadtest.local'
# 로그인하지 않으므로 값은 무관하다. 형식($2a$10$ + 53자)만 맞춘다
PASSWORD_HASH='$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234'

for command in docker curl jq openssl; do
  command -v "$command" >/dev/null || { printf '필요한 도구가 없습니다: %s\n' "$command" >&2; exit 1; }
done
# shellcheck source=lib/jwt.sh
source "$ROOT/scripts/loadtest/lib/jwt.sh"
mkdir -p "$OUT"

psql_run() {
  docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -qAt "$@"' sh "$@"
}

usage() {
  echo '사용: seed.sh members [--count N] | seed.sh schedule --scenario queue-surge|hot-seats|seat-conflict-refund' >&2
  exit 1
}

seed_members() {
  local count=10000
  while (($#)); do
    case "$1" in
      --count) [[ $# -ge 2 && $2 =~ ^[0-9]+$ ]] || { echo '--count에는 숫자가 필요합니다.' >&2; exit 1; }; count=$2; shift 2 ;;
      *) printf '알 수 없는 옵션: %s\n' "$1" >&2; exit 1 ;;
    esac
  done
  psql_run -v "hash=$PASSWORD_HASH" -v "count=$count" -v "organizer=$ORGANIZER_EMAIL" <<'SQL'
INSERT INTO member.member (email, password_hash, name, role, created_at)
SELECT 'load-' || lpad(n::text, 5, '0') || '@loadtest.local', :'hash', 'load-' || n, 'USER', now()
FROM generate_series(1, :count) AS n
ON CONFLICT DO NOTHING;
INSERT INTO member.member (email, password_hash, name, role, created_at)
VALUES (:'organizer', :'hash', 'load-organizer', 'ORGANIZER', now())
ON CONFLICT DO NOTHING;
SQL
  local range organizer
  range=$(psql_run -F ' ' -c "SELECT min(id), max(id), count(*) FROM member.member WHERE role = 'USER' AND email LIKE 'load-%@loadtest.local'")
  organizer=$(psql_run -c "SELECT id FROM member.member WHERE email = '$ORGANIZER_EMAIL'")
  read -r min max total <<<"$range"
  [[ -n $min && -n $organizer ]] || { echo '회원 시드 결과를 읽지 못했습니다.' >&2; exit 1; }
  jq -n --argjson min "$min" --argjson max "$max" --argjson count "$total" --argjson organizerId "$organizer" \
    '{userIdMin: $min, userIdMax: $max, userCount: $count, organizerId: $organizerId}' > "$OUT/members.json"
  printf '회원 시드 완료: USER %s명(ID %s~%s), 주최자 ID %s\n' "$total" "$min" "$max" "$organizer"
}

api() { # api <token> <method> <path> [body]  -> 본문은 stdout, 상태 코드는 마지막 줄
  local token=$1 method=$2 path=$3 body=${4:-}
  local args=(-sS -X "$method" -H "Authorization: Bearer $token" -w '\n%{http_code}')
  [[ -z $body ]] || args+=(-H 'Content-Type: application/json' --data-binary @-)
  printf '%s' "$body" | curl "${args[@]}" "$CORE_URL$path"
}

api_ok() { # 2xx가 아니면 실패 메시지를 남기고 종료한다. 본문만 출력한다
  local response status
  response=$(api "$@") || { printf 'core 호출 실패: %s %s\n' "$2" "$3" >&2; exit 1; }
  status=${response##*$'\n'}
  if [[ ! $status =~ ^2 ]]; then
    printf '%s %s 실패(HTTP %s): %s\n' "$2" "$3" "$status" "${response%$'\n'*}" >&2
    exit 1
  fi
  printf '%s\n' "${response%$'\n'*}"
}

venue_payload() {
  jq -n '{name: "loadtest-venue", seats: [
    ("A","B","C","D","E") as $s | range(1; 26) as $r | range(1; 41) as $n
    | {section: $s, row: ($r | tostring), number: $n,
       grade: (if $r <= 2 then "VIP" elif $r <= 8 then "R" elif $r <= 16 then "S" else "A" end)}
  ]}'
}

register_performance() { # <token> <venueId> <title> -> HTTP 응답(본문+상태)
  local body
  body=$(jq -nc --arg title "$3" --argjson venue "$2" \
    '{title: $title, description: "loadtest", venueId: $venue,
      prices: [{grade: "VIP", price: 150000}, {grade: "R", price: 120000}, {grade: "S", price: 90000}, {grade: "A", price: 60000}]}')
  api "$1" POST /api/v1/performances "$body"
}

seed_schedule() {
  local scenario=
  while (($#)); do
    case "$1" in
      --scenario) [[ $# -ge 2 ]] || usage; scenario=$2; shift 2 ;;
      *) printf '알 수 없는 옵션: %s\n' "$1" >&2; exit 1 ;;
    esac
  done
  [[ $scenario == queue-surge || $scenario == hot-seats || $scenario == seat-conflict-refund ]] \
    || { echo '--scenario는 queue-surge, hot-seats, seat-conflict-refund 중 하나여야 합니다.' >&2; exit 1; }
  [[ -f $OUT/members.json ]] || { echo 'build/loadtest/members.json이 없습니다. 먼저 seed.sh members를 실행하세요.' >&2; exit 1; }

  local organizer token venue_file="$OUT/venue.json" venue_id title response status
  organizer=$(jq -r .organizerId "$OUT/members.json")
  token=$(sign_jwt "$organizer" ORGANIZER)
  title="loadtest-$scenario-$(date +%Y%m%d%H%M%S)"

  # 공연장 조회 API가 없어 ID를 파일로 재사용한다. DB가 초기화돼 ID가 사라졌으면(404) 다시 만든다
  local performance_id=
  if [[ -f $venue_file ]]; then
    venue_id=$(jq -r .venueId "$venue_file")
    response=$(register_performance "$token" "$venue_id" "$title")
    status=${response##*$'\n'}
    if [[ $status =~ ^2 ]]; then
      performance_id=$(jq -r .id <<<"${response%$'\n'*}")
    else
      printf '저장된 공연장(%s)을 쓸 수 없어(HTTP %s) 새로 만듭니다.\n' "$venue_id" "$status" >&2
      rm -f "$venue_file"
    fi
  fi
  if [[ -z $performance_id ]]; then
    venue_id=$(api_ok "$token" POST /api/v1/venues "$(venue_payload)" | jq -r .id)
    jq -n --argjson venueId "$venue_id" '{venueId: $venueId}' > "$venue_file"
    response=$(register_performance "$token" "$venue_id" "$title")
    status=${response##*$'\n'}
    [[ $status =~ ^2 ]] || { printf '공연 등록 실패(HTTP %s): %s\n' "$status" "${response%$'\n'*}" >&2; exit 1; }
    performance_id=$(jq -r .id <<<"${response%$'\n'*}")
  fi

  local starts_at schedule_id seats seat_count
  starts_at=$(date -u -d '+7 days' +%Y-%m-%dT%H:%M:%SZ)
  schedule_id=$(api_ok "$token" POST "/api/v1/performances/$performance_id/schedules" "{\"startsAt\":\"$starts_at\"}" | jq -r .id)
  seats=$(curl -fsS "$CORE_URL/api/v1/schedules/$schedule_id/seats")
  seat_count=$(jq length <<<"$seats")
  ((seat_count > 0)) || { echo '좌석 목록이 비어 있습니다.' >&2; exit 1; }

  # ScheduleRegistered 이벤트가 Redis 스냅샷을 만들 때까지 기다린다
  local key="booking:seats:{$schedule_id}" deadline=$((SECONDS + 10)) size=0
  until [[ $size == "$seat_count" ]]; do
    size=$(docker compose exec -T redis redis-cli HLEN "$key" | tr -d '\r')
    [[ $size == "$seat_count" ]] && break
    ((SECONDS < deadline)) || { printf 'Redis 스냅샷이 10초 안에 만들어지지 않았습니다(%s, %s/%s).\n' "$key" "$size" "$seat_count" >&2; exit 1; }
    sleep 0.5
  done

  jq -n --argjson scheduleId "$schedule_id" --argjson performanceId "$performance_id" --slurpfile seatsFile <(printf '%s' "$seats") '
    $seatsFile[0] as $seats
    | ($seats | map(.seatId)) as $all
    | ([$seats[] | select(.section == "A" and (.row == "1" or .row == "2")) | .seatId]
       + [$seats | map(select(.section == "B" and .row == "1")) | sort_by(.number) | .[:20][] | .seatId]) as $hot
    | ($all | sort) as $sorted
    # seat-conflict-refund: 서로 겹치지 않는 3석 묶음마다 A는 앞 2석, B는 뒤 2석(가운데 1석이 겹침)을 예매한다. 규모는 100쌍으로 고정한다
    | {scheduleId: $scheduleId, performanceId: $performanceId, seatIds: $all, hotSeatIds: $hot,
       conflictPairs: [range(0; 100) | . as $p | ($p * 3) as $i
         | {pair: ($p + 1), seatsA: [$sorted[$i], $sorted[$i + 1]], seatsB: [$sorted[$i + 1], $sorted[$i + 2]], overlapSeatId: $sorted[$i + 1]}]}' \
    > "$OUT/schedule-$scenario.json"
  printf '회차 등록 완료: scheduleId=%s, 좌석 %s석, hot %s석 -> build/loadtest/schedule-%s.json\n' \
    "$schedule_id" "$seat_count" "$(jq '.hotSeatIds | length' "$OUT/schedule-$scenario.json")" "$scenario"
}

[[ $# -ge 1 ]] || usage
cmd=$1; shift
case "$cmd" in
  members) seed_members "$@" ;;
  schedule) seed_schedule "$@" ;;
  *) usage ;;
esac
