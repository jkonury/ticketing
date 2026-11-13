#!/usr/bin/env bash
# 사용: prom-snapshot.sh <from-epoch> <to-epoch> <outdir>
# 실행 구간의 서버 측 지표를 Prometheus HTTP API로 뽑아 <outdir>/prometheus.json에 저장한다.
# PROMETHEUS_URL로 주소를 바꿀 수 있다(기본 http://localhost:19090).
set -euo pipefail

FROM=${1:-}
TO=${2:-}
OUTDIR=${3:-}
if [[ ! $FROM =~ ^[0-9]+$ || ! $TO =~ ^[0-9]+$ || -z $OUTDIR ]]; then
  echo '사용: prom-snapshot.sh <from-epoch> <to-epoch> <outdir>' >&2
  exit 1
fi
((TO > FROM)) || { echo 'to-epoch는 from-epoch보다 커야 합니다.' >&2; exit 1; }
for command in curl jq; do
  command -v "$command" >/dev/null || { printf '필요한 도구가 없습니다: %s\n' "$command" >&2; exit 1; }
done
mkdir -p "$OUTDIR"

PROM=${PROMETHEUS_URL:-http://localhost:19090}
STEP=5
# 마지막 scrape(5초 간격)가 들어올 시간을 두고, 구간 끝을 실행 종료보다 6초 뒤로 잡는다
END=$((TO + 6))
DUR=$((END - FROM))

# 시계열마다 최대·평균·처음·마지막 값과 카운터 증가량(리셋 보정)을 계산한다
range_stats() {
  curl -fsS "$PROM/api/v1/query_range" --data-urlencode "query=$1" \
    --data-urlencode "start=$FROM" --data-urlencode "end=$END" --data-urlencode "step=$STEP" \
    | jq -c '
      if .status != "success" then error("query_range 실패: \(.error // "unknown")") else . end
      | [.data.result[] | {
          metric: .metric,
          v: [.values[] | .[1] | select(. != "NaN" and . != "+Inf" and . != "-Inf") | tonumber]
        } | select(.v | length > 0) | {
          metric,
          max: (.v | max),
          avg: ((.v | add) / (.v | length)),
          first: .v[0],
          last: .v[-1],
          delta: (.v as $v | reduce range(1; $v | length) as $i (0; . + (if $v[$i] >= $v[$i - 1] then $v[$i] - $v[$i - 1] else $v[$i] end)))
        }]'
}

instant() {
  curl -fsS "$PROM/api/v1/query" --data-urlencode "query=$1" --data-urlencode "time=$END" \
    | jq -c '
      if .status != "success" then error("query 실패: \(.error // "unknown")") else . end
      | [.data.result[] | select(.value[1] != "NaN") | {metric: .metric, value: (.value[1] | tonumber)}]'
}

# 앱을 실행마다 새로 띄워 카운터가 0에서 시작하므로, 구간 안 마지막 표본(누적값)이 이번 실행의 값이다.
# increase()는 구간 중간에 처음 생긴 시계열의 첫 표본을 빼서 짧은 급증(시나리오 B)을 놓치므로 쓰지 않는다.
bucket_q() { # <분위수> : 구간 전체의 앱·URI별 분위수(초)
  instant "histogram_quantile($1, sum by (le, application, uri) (last_over_time(http_server_requests_seconds_bucket[${DUR}s])))"
}

p95=$(bucket_q 0.95)
p99=$(bucket_q 0.99)
count=$(instant "sum by (application, uri) (last_over_time(http_server_requests_seconds_count[${DUR}s]))")
rps=$(range_stats 'sum by (application, uri) (rate(http_server_requests_seconds_count[30s]))')
pending=$(range_stats 'hikaricp_connections_pending')
active=$(range_stats 'hikaricp_connections_active')
acquire_sum=$(range_stats 'hikaricp_connections_acquire_seconds_sum')
acquire_count=$(range_stats 'hikaricp_connections_acquire_seconds_count')
acquire_max=$(range_stats 'hikaricp_connections_acquire_seconds_max')
timeout=$(range_stats 'hikaricp_connections_timeout_total')
cpu=$(range_stats 'process_cpu_usage')
heap=$(range_stats 'sum by (application) (jvm_memory_used_bytes{area="heap"})')
gc=$(range_stats 'sum by (application) (jvm_gc_pause_seconds_sum)')
vt_count=$(range_stats 'jvm_threads_virtual_pinned_seconds_count')
vt_max=$(range_stats 'jvm_threads_virtual_pinned_seconds_max')
vt_failed=$(range_stats 'jvm_threads_virtual_submit_failed_total')
redis=$(range_stats 'rate(redis_commands_processed_total[1m])')
pg=$(range_stats 'sum(pg_stat_database_numbackends{datname!~"template.*|postgres"})')
gateway_buckets=$(instant "last_over_time(spring_cloud_gateway_requests_seconds_bucket[${DUR}s])")
hikari_buckets=$(instant "last_over_time(hikaricp_connections_acquire_seconds_bucket[${DUR}s])")
pool_active=$(range_stats 'reactor_netty_connection_provider_active_connections')
pool_idle=$(range_stats 'reactor_netty_connection_provider_idle_connections')
pool_pending=$(range_stats 'reactor_netty_connection_provider_pending_connections')
pool_wait=$(instant "last_over_time(reactor_netty_connection_provider_pending_connections_time_seconds_bucket[${DUR}s])")
queue_metrics=$(instant "{__name__=~\"queue_(registration|departure|admission|presence).*\"}")

snapshot_tmp=$(mktemp -d)
trap 'rm -rf "$snapshot_tmp"' EXIT
printf '%s' "$gateway_buckets" > "$snapshot_tmp/gateway.json"
printf '%s' "$hikari_buckets" > "$snapshot_tmp/hikari.json"
printf '%s' "$pool_active" > "$snapshot_tmp/active.json"
printf '%s' "$pool_idle" > "$snapshot_tmp/idle.json"
printf '%s' "$pool_pending" > "$snapshot_tmp/pending.json"
printf '%s' "$pool_wait" > "$snapshot_tmp/wait.json"
printf '%s' "$queue_metrics" > "$snapshot_tmp/queue.json"
jq -n \
  --argjson from "$FROM" --argjson to "$TO" --argjson dur "$DUR" --argjson step "$STEP" \
  --argjson p95 "$p95" --argjson p99 "$p99" --argjson count "$count" --argjson rps "$rps" \
  --argjson pending "$pending" --argjson active "$active" \
  --argjson acquireSum "$acquire_sum" --argjson acquireCount "$acquire_count" --argjson acquireMax "$acquire_max" \
  --argjson timeout "$timeout" --argjson cpu "$cpu" --argjson heap "$heap" --argjson gc "$gc" \
  --argjson vtCount "$vt_count" --argjson vtMax "$vt_max" --argjson vtFailed "$vt_failed" \
  --argjson redis "$redis" --argjson pg "$pg" \
  --slurpfile gatewayBuckets "$snapshot_tmp/gateway.json" --slurpfile hikariBuckets "$snapshot_tmp/hikari.json" \
  --slurpfile poolActive "$snapshot_tmp/active.json" --slurpfile poolIdle "$snapshot_tmp/idle.json" --slurpfile poolPending "$snapshot_tmp/pending.json" --slurpfile poolWait "$snapshot_tmp/wait.json" \
  --slurpfile queueMetrics "$snapshot_tmp/queue.json" '
  def key: "\(.metric.application)\t\(.metric.uri)";
  def byApp(f): [.[] | {key: .metric.application, value: f}] | from_entries;
  def appMap(a): (a | map({(.metric.application): .}) | add // {});
  def at(m; k): (m[k] // null);
  {
    window: {fromEpoch: $from, toEpoch: $to, durationSeconds: $dur, stepSeconds: $step},
    gatewayRouteBuckets: $gatewayBuckets[0],
    hikariAcquireBuckets: $hikariBuckets[0],
    gatewayConnectionPool: {active: $poolActive[0], idle: $poolIdle[0], pending: $poolPending[0], pendingTimeBuckets: $poolWait[0]},
    queueMetrics: $queueMetrics[0],
    note: "p95·p99는 구간 안 마지막 누적 분포(앱이 실행마다 새로 떠서 이번 실행의 값)이고 rps는 rate[30s]의 최대값이다(짧은 급증은 5초 scrape로 놓칠 수 있어 requests로 평균을 구한다). gauge 최대값은 \($step)초 간격 표본이라 그 사이의 순간 값은 놓칠 수 있다.",
    http: (
      ($p95 | map({(key): .value}) | add // {}) as $p95m
      | ($p99 | map({(key): .value}) | add // {}) as $p99m
      | ($count | map({(key): .value}) | add // {}) as $cm
      | ($rps | map({(key): .max}) | add // {}) as $rm
      | [($p95 + $p99 + $count | .[] | {application: .metric.application, uri: .metric.uri}), ($rps[] | {application: .metric.application, uri: .metric.uri})]
      | unique
      | map(. as $u | "\($u.application)\t\($u.uri)" as $k | $u + {
          requests: ($cm[$k] // 0 | round), p95Seconds: $p95m[$k], p99Seconds: $p99m[$k], rpsMax: $rm[$k]
        })
      | map(select(.requests > 0 or .rpsMax != null))
    ),
    hikari: (
      appMap($pending) as $pe | appMap($active) as $ac | appMap($acquireSum) as $as | appMap($acquireCount) as $acn
      | appMap($acquireMax) as $am | appMap($timeout) as $to
      | [$pe, $ac] | map(keys) | add | unique
      | map({key: ., value: {
          pendingMax: $pe[.].max, activeMax: $ac[.].max,
          acquireCount: ($acn[.].delta // null), acquireAvgSeconds: (if ($acn[.].delta // 0) > 0 then ($as[.].delta / $acn[.].delta) else null end),
          acquireMaxSeconds: $am[.].max, timeouts: ($to[.].delta // null)
        }}) | from_entries
    ),
    cpu: ($cpu | byApp({max, avg})),
    heapMaxBytes: ($heap | byApp(.max)),
    gcPauseSeconds: ($gc | byApp(.delta)),
    virtualThreads: (
      appMap($vtCount) as $vc | appMap($vtMax) as $vm | appMap($vtFailed) as $vf
      | ($vc | keys) | map({key: ., value: {pinnedEvents: $vc[.].delta, pinnedMaxSeconds: $vm[.].max, submitFailed: ($vf[.].delta // null)}}) | from_entries
    ),
    redisCommandsPerSecMax: ($redis | map(.max) | max),
    postgresBackendsMax: ($pg | map(.max) | max)
  }' > "$OUTDIR/prometheus.json"

printf 'prometheus.json 저장: %s\n' "$OUTDIR/prometheus.json"
