#!/usr/bin/env bash
# source 전용. 필요한 전역: ROOT, OUT, TOOL, OBSERVABILITY 등 지표 수집 변수.
# 의존 함수: 없음(호출 시점에 run.sh 전역을 참조한다).

# dump_codelist <디렉터리> <pre|burst>: core·gateway의 컴파일된 메서드 목록(진단 전용, 실패해도 실행에 영향 없음)
dump_codelist() {
  local dir=$1 tag=$2 name pid
  for name in core gateway; do
    pid=${PIDS[$([[ $name == core ]] && echo 1 || echo 3)]}
    date +%s.%N > "$dir/codelist-$name-$tag.time"
    jcmd "$pid" Compiler.codelist > "$dir/codelist-$name-$tag.txt" 2>"$dir/codelist-$name-$tag.err" || true
  done
}

# collect_metrics <출력 파일>: 앱 지표(누적 카운터)를 직접 읽는다. 관측 스택이 있으면 Prometheus 마지막 scrape 시각도 남긴다
collect_metrics() {
  local prom=()
  if $OBSERVABILITY; then prom=(--prometheus http://localhost:19090); fi
  python3 "$ROOT/scripts/loadtest/metrics-delta.py" collect "$1" "${prom[@]}" "${METRIC_TARGETS[@]}"
}

publish_dashboard() {
  [[ $TOOL == gatling ]] || return 0
  python3 "$ROOT/scripts/loadtest/dashboard-metrics.py" publish "$@"
}
