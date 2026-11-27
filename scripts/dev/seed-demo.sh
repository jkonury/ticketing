#!/usr/bin/env bash
# 사용: seed-demo.sh [--reset [--yes] [--dry-run]]
# 화면 시연용 공연 데이터를 API로 만든다. 로컬 docker compose 스택 전용이다.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
GATEWAY_URL=${GATEWAY_URL:-http://localhost:18000}
PASSWORD='demo-password-123'
ORGANIZER_EMAIL='demo-organizer@openseat.local'
USER_EMAIL='demo-user@openseat.local'
ADMIN_EMAIL='demo-admin@openseat.local'

for command in docker curl jq date; do
  command -v "$command" >/dev/null || { printf '필요한 도구가 없습니다: %s\n' "$command" >&2; exit 1; }
done

RESET=false YES=false DRY_RUN=false
while (($#)); do
  case "$1" in
    --reset) RESET=true ;;
    --yes) YES=true ;;
    --dry-run) DRY_RUN=true ;;
    *) printf '알 수 없는 옵션: %s\n사용: seed-demo.sh [--reset [--yes] [--dry-run]]\n' "$1" >&2; exit 1 ;;
  esac
  shift
done
$DRY_RUN && ! $RESET && { echo '--dry-run은 --reset과 함께 써야 합니다.' >&2; exit 1; }

require_local_compose() {
  if [[ -n ${DOCKER_HOST:-} && $DOCKER_HOST != unix://* ]]; then
    echo '원격 Docker 호스트로는 실행하지 않습니다(로컬 docker compose 전용).' >&2; exit 1
  fi
  [[ -n $(docker compose ps -q postgres 2>/dev/null) ]] \
    || { echo '로컬 docker compose의 postgres 컨테이너가 떠 있지 않아요.' >&2; exit 1; }
}

# 컨테이너 안의 환경 변수를 쓰므로 .env를 읽지 않는다. $1은 DB 이름 변수명
psql_run() {
  local db_var=$1; shift
  docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$(eval echo \$'"$db_var"')" -v ON_ERROR_STOP=1 -qAt "$@"' sh "$@"
}

truncate_sql() { # <db 변수명> -> TRUNCATE 문. 시스템 스키마와 flyway_schema_history 제외
  local tables
  tables=$(psql_run "$1" -c "SELECT string_agg(format('%I.%I', table_schema, table_name), ', ' ORDER BY table_schema, table_name)
    FROM information_schema.tables
    WHERE table_type = 'BASE TABLE' AND table_schema <> 'information_schema' AND table_schema NOT LIKE 'pg\_%'
      AND table_name <> 'flyway_schema_history'")
  [[ -z $tables ]] || printf 'TRUNCATE TABLE %s RESTART IDENTITY CASCADE;\n' "$tables"
}

reset_all() {
  require_local_compose
  local main_sql payment_sql
  main_sql=$(truncate_sql POSTGRES_DB)
  payment_sql=$(truncate_sql TICKETING_PAYMENT_POSTGRES_DB)
  if $DRY_RUN; then
    printf -- '-- main DB\n%s\n-- payment DB\n%s\n-- redis: FLUSHALL\n' "$main_sql" "$payment_sql"
    exit 0
  fi
  if ! $YES; then
    read -r -p '로컬 DB(메인·payment)와 Redis의 모든 데이터를 지워요. 계속할까요? [y/N] ' answer
    [[ $answer == y || $answer == Y ]] || { echo '취소했어요.'; exit 1; }
  fi
  [[ -z $main_sql ]] || printf '%s\n' "$main_sql" | psql_run POSTGRES_DB
  [[ -z $payment_sql ]] || printf '%s\n' "$payment_sql" | psql_run TICKETING_PAYMENT_POSTGRES_DB
  docker compose exec -T redis redis-cli FLUSHALL >/dev/null
  echo '초기화했어요.'
}

api() { # api <token|-> <method> <path> [body] -> 본문, 마지막 줄에 상태 코드
  local token=$1 method=$2 path=$3 body=${4:-}
  local args=(-sS -X "$method" -w '\n%{http_code}')
  [[ $token == - ]] || args+=(-H "Authorization: Bearer $token")
  [[ -z $body ]] || args+=(-H 'Content-Type: application/json' --data-binary @-)
  printf '%s' "$body" | curl "${args[@]}" "$GATEWAY_URL/api/v1$path"
}

api_ok() { # 2xx가 아니면 종료. 본문만 출력
  local response status
  response=$(api "$@") || { printf 'Gateway 호출 실패: %s %s\n' "$2" "$3" >&2; exit 1; }
  status=${response##*$'\n'}
  if [[ ! $status =~ ^2 ]]; then
    printf '%s %s 실패(HTTP %s): %s\n' "$2" "$3" "$status" "${response%$'\n'*}" >&2
    exit 1
  fi
  printf '%s\n' "${response%$'\n'*}"
}

login() { # <email> -> accessToken (실패 시 빈 값)
  local response
  response=$(api - POST /auth/tokens "$(jq -nc --arg e "$1" --arg p "$PASSWORD" '{email:$e,password:$p}')") || return 0
  [[ ${response##*$'\n'} == 200 ]] && jq -r .accessToken <<<"${response%$'\n'*}"
  return 0
}

signup() { # <email> <name> <role>
  api_ok - POST /members "$(jq -nc --arg e "$1" --arg p "$PASSWORD" --arg n "$2" --arg r "$3" \
    '{email:$e,password:$p,name:$n,role:$r}')" | jq -r .id
}

# 좌석 배치: 구역·열·번호와 등급은 jq 식으로 정한다
small_seats() { jq -nc '[range(1;9) as $r | range(1;11) as $n | {section:"A",row:($r|tostring),number:$n,grade:(if $r<=4 then "R" else "S" end)}]'; }
mid_seats() { jq -nc '[("A","B","C") as $s | range(1;11) as $r | range(1;8) as $n
  | {section:$s,row:($r|tostring),number:$n,
     grade:(if $s=="A" then "VIP" elif $s=="B" then "R" else "S" end)}] | .[0:200]'; }
hall_seats() { jq -nc '[("1층 A","1층 B","1층 C","2층 D") as $s | range(1;9) as $r | range(1;11) as $n
  | {section:$s,row:($r|tostring),number:$n,
     grade:(if $s=="2층 D" then "A" elif $r<=2 then "VIP" elif $r<=5 then "R" else "S" end)}] | .[0:300]'; }

create_venue() { # <token> <name> <seats json>
  api_ok "$1" POST /venues "$(jq -nc --arg n "$2" --argjson s "$3" '{name:$n,seats:$s}')" | jq -r .id
}

start_at() { # <일수> <HH:MM>
  TZ=Asia/Seoul date -d "+$1 days $2" +%Y-%m-%dT%H:%M:00+09:00
}

upload_poster() {
  local performance=$1 poster=$2 upload url upload_id header
  local headers=()
  upload=$(api_ok "$organizer_token" POST "/performances/$performance/poster-uploads" '{"contentType":"image/webp"}')
  url=$(jq -er .url <<<"$upload")
  upload_id=$(jq -er .uploadId <<<"$upload")
  while IFS= read -r header; do
    headers+=(-H "$header")
  done < <(jq -r '.headers | to_entries[] | "\(.key): \(.value)"' <<<"$upload")
  curl -fsS --max-time 30 "${headers[@]}" --upload-file "$poster" "$url" >/dev/null
  api_ok "$organizer_token" POST "/performances/$performance/poster-uploads/$upload_id/complete" >/dev/null
}

# 공연: 공연장 키|제목|소개|가격(JSON)|회차(일수@시각 ...)|장르|포스터 파일
PERFORMANCES=$(cat <<'DATA'
mid|달빛 아래 우체국|편지 한 통이 이어 준 두 사람의 십 년을 그린 창작 뮤지컬이에요. 따뜻한 선율과 앙상블이 어우러져요. 가족 단위 관객에게도 잘 맞아요.|{"VIP":150000,"R":110000,"S":80000}|5@19:30 12@14:00 19@19:30|MUSICAL
hall|겨울밤 클래식 갈라|현악 사중주와 피아노 협연으로 꾸린 송년 갈라 무대예요. 익숙한 소품부터 대편성 관현악까지 폭넓게 들려드려요.|{"VIP":170000,"R":130000,"S":90000,"A":60000}|9@19:30 16@19:30|CLASSICAL
small|두 개의 문|한 방에서 마주친 두 형제가 오래 묵은 비밀을 꺼내는 이인극이에요. 짧고 밀도 있는 대사가 긴장감을 만들어요.|{"R":50000,"S":40000}|3@19:30 10@19:30 17@14:00|PLAY
hall|네온 시티 라이브|신스 사운드와 밴드 라이브를 결합한 도시 감성 콘서트예요. 영상과 조명 연출이 공연을 채워요.|{"VIP":165000,"R":132000,"S":99000,"A":77000}|14@19:30 15@19:30|CONCERT|scripts/dev/posters/neon-city-live.webp
mid|웃음 공장 스탠드업|일상의 작은 불편을 입담으로 풀어내는 코미디 쇼예요. 매회 새 소재로 꾸며요.|{"VIP":60000,"R":45000,"S":35000}|7@19:30 21@19:30|PLAY
small|오후의 재즈 살롱|트리오 편성의 재즈 연주를 가까이에서 듣는 낮 공연이에요. 차 한 잔과 함께 편하게 즐겨요.|{"R":45000,"S":35000}|4@14:00 11@14:00 18@14:00|CONCERT
mid|숲속의 작은 오케스트라|아이와 함께 악기 소리를 배우는 가족 음악극이에요. 직접 소리를 내 보는 시간도 있어요.|{"VIP":70000,"R":55000,"S":40000}|6@14:00 13@14:00|MUSICAL
hall|라스트 발레 나이트|젊은 무용수들이 고전과 창작 안무를 번갈아 선보이는 발레 갈라예요. 섬세한 몸짓에 집중하는 밤이에요.|{"VIP":140000,"R":105000,"S":75000,"A":50000}|25@19:30 30@19:30|CLASSICAL
DATA
)

if $RESET; then reset_all; fi

require_local_compose
curl -s -o /dev/null --max-time 5 "$GATEWAY_URL/api/v1/performances?size=1" \
  || { printf 'Gateway(%s)에 연결할 수 없어요. 스택을 먼저 띄워 주세요.\n' "$GATEWAY_URL" >&2; exit 1; }

organizer_token=$(login "$ORGANIZER_EMAIL")
if [[ -n $organizer_token ]]; then
  echo '이미 시드돼 있어요. 초기화하려면 --reset'
  exit 0
fi

signup "$ORGANIZER_EMAIL" '오픈석 기획' ORGANIZER >/dev/null
user_id=$(signup "$USER_EMAIL" '김관람' USER)
signup "$ADMIN_EMAIL" '운영자' USER >/dev/null
# ADMIN은 가입으로 얻을 수 없다(ADR 0034). 가입한 회원을 DB에서 승격한다
promoted=$(psql_run POSTGRES_DB -c "UPDATE member.member SET role='ADMIN' WHERE email='$ADMIN_EMAIL' RETURNING 1")
[[ $promoted == 1 ]] || { echo '관리자 승격에 실패했어요.' >&2; exit 1; }

organizer_token=$(login "$ORGANIZER_EMAIL")
admin_token=$(login "$ADMIN_EMAIL")
[[ -n $organizer_token && -n $admin_token ]] || { echo '시드 계정 로그인에 실패했어요.' >&2; exit 1; }

declare -A VENUE
VENUE[small]=$(create_venue "$organizer_token" '아담 소극장' "$(small_seats)")
VENUE[mid]=$(create_venue "$organizer_token" '한빛 중극장' "$(mid_seats)")
VENUE[hall]=$(create_venue "$organizer_token" '새봄 콘서트홀' "$(hall_seats)")

performances=0 schedules=0
while IFS='|' read -r venue title description prices slots genre poster; do
  body=$(jq -nc --arg t "$title" --arg d "$description" --arg g "$genre" --argjson v "${VENUE[$venue]}" --argjson p "$prices" \
    '{title:$t,description:$d,genre:$g,venueId:$v,prices:($p | to_entries | map({grade:.key,price:.value}))}')
  performance_id=$(api_ok "$organizer_token" POST /performances "$body" | jq -r .id)
  performances=$((performances + 1))
  for slot in $slots; do
    starts=$(start_at "${slot%@*}" "${slot#*@}")
    api_ok "$organizer_token" POST "/performances/$performance_id/schedules" "$(jq -nc --arg s "$starts" '{startsAt:$s}')" >/dev/null
    schedules=$((schedules + 1))
  done
  [[ -z $poster ]] || upload_poster "$performance_id" "$poster"
done <<<"$PERFORMANCES"

now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
until=$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ)
coupon_body=$(jq -nc --arg f "$now" --arg u "$until" '{name:"오픈 기념 5,000원 할인",description:"전 공연 정액 할인",
  discountType:"FIXED",discountValue:5000,maxDiscount:null,minOrderAmount:0,performanceId:null,
  validFrom:$f,validUntil:$u,issueMode:"DIRECT",totalQuantity:null}')
coupon_id=$(api_ok "$admin_token" POST /admin/coupons "$coupon_body" | jq -r .id)
api_ok "$admin_token" POST "/admin/coupons/$coupon_id/grants" "$(jq -nc --argjson m "$user_id" '{memberIds:[$m]}')" >/dev/null

cat <<SUMMARY
시드를 마쳤어요. (비밀번호는 모두 $PASSWORD)
  주최자  $ORGANIZER_EMAIL
  관람객  $USER_EMAIL (쿠폰 1장 지급)
  운영자  $ADMIN_EMAIL
공연 ${performances}개, 회차 ${schedules}개, 공연장 3개를 만들었어요.
SUMMARY
# shellcheck disable=SC1091
web_origin=$(set -a; [[ -f .env ]] && source .env; [[ -f web/.env.local ]] && source web/.env.local; set +a; printf '%s' "${WEB_ORIGIN:-}")
if [[ -n $web_origin ]]; then
  printf '데이터를 바꾸는 요청은 WEB_ORIGIN 도메인으로 접속해야 해요: %s\n' "$web_origin"
fi
