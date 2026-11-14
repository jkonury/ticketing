#!/usr/bin/env bash
# 부하 중 1초마다 pg_stat_activity를 기록한다. 인자: <k6 pid> <출력 파일>
# 연결을 오래 쥐는 세션(idle in transaction, 긴 xact)이 풀 대기의 원인인지 확인하는 용도다.
set -u
k6_pid=$1
out=$2
container=${POSTGRES_CONTAINER:-ticketing-postgres-1}
query="select to_char(now(),'HH24:MI:SS'), coalesce(state,'-'), coalesce(wait_event_type,'-'), count(*),
  coalesce(max(extract(epoch from now()-xact_start))::numeric(8,3),0),
  left(coalesce(min(query),''),70)
  from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and backend_type='client backend'
  group by 2,3 order by 2,3"
printf 'time|state|wait_event_type|connections|max_xact_age_sec|query_sample\n' > "$out"
while kill -0 "$k6_pid" 2>/dev/null; do
  docker exec "$container" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -c "$1"' _ "$query" >> "$out" 2>&1 || true
  sleep 1
done
