#!/usr/bin/env bash
# source 전용. 필요한 전역: 옵션 기본값 변수(SCENARIO, RUNS, TOOL 등)를 먼저 선언해 둔다.
# 사용: parse_args "$@"; validate_args

usage() {
  cat >&2 <<'USAGE'
사용: run.sh --scenario <smoke|queue-surge|hot-seats|seat-conflict-refund|coupon-drop> [--runs N] [--failure-inject] [--jfr] [--observability] [--keep]
            [--vus N] [--k6-cpus 0-3] [--app-cpus 4-15] [--no-scouter]
            [--warmup [--warmup-rounds N]] [--internal-warmup] [--codelist] [--restart-after-warmup core,gateway|gateway|realtime] [--no-rate-limit] [--aot-cache [--aot-train-rounds N]]
            [--tool k6|gatling] [--gatling-heap 2g] [--gatling-report] [--booking-p95-ms N] [--jvm-opts '-XX:옵션 ...']
--warmup은 같은 앱(JVM 재시작 없음)에서 별도 회차로 같은 시나리오 부하를 N회 보낸 뒤, 비동기 처리(결제 웹훅·확정, 대기열 정리)가
끝났는지 확인하고 새 회차·좌석으로 측정합니다. 기본은 워밍업 없는 콜드 실행입니다.
--internal-warmup은 core·gateway·payment readiness 전 내부 예열을 켭니다. 외부 --warmup·--aot-cache와 함께 쓰지 않으며 별도 계열로 기록합니다.
--warmup-rounds는 워밍업 횟수입니다(기본 5). Gatling 회차별 결과는 warmup-<n>/, settle은 warmup-settle-<n>.json에 남깁니다.
서버 지표는 본 부하 직전·직후 직접 수집한 누적 카운터의 차분(server-delta.json)으로 계산합니다.
--aot-cache는 core·gateway·realtime·payment를 jar로 풀어 학습 기동(부하 N회, 기본 15)으로 AOT 캐시를 만든 뒤, 측정 기동마다 그 캐시로 띄웁니다(워밍업 요청 없음).
--no-rate-limit은 Gateway rate limiter를 끄는 대조 실험용입니다.
--restart-after-warmup은 진단 전용으로 --warmup 15회 직후 지정한 서비스(쉼표 구분)만 새 JVM으로 다시 띄웁니다. core·gateway는 내부 예열을 켜고 realtime은 예열 없이 띄웁니다. 재기동 서비스의 이전 로그는 <서비스>-prewarm.log로 남기며 워밍업 구간 차분 검사는 건너뜁니다.
--codelist는 진단 전용으로 본 부하 직전과 CODELIST_DELAY초(기본 52, 예매 폭주 직전) 뒤에 core·gateway의 jcmd Compiler.codelist를 codelist-<서비스>-<pre|burst>.txt로 남깁니다.
--jvm-opts는 core·gateway JVM에만 추가하는 -XX 옵션입니다(예: '-XX:CompileThresholdScaling=0.25'). 컴파일 임계 대조용이며 env.txt에 기록합니다.
SCOUTER_AGENT_DIR을 지정하면 Java 앱에 Scouter를 연결합니다.
--no-scouter는 .env의 Scouter 설정도 끄고 대조 측정합니다.
seat-conflict-refund는 선점 키 유실을 주입한 보상 정합성 검증입니다(100쌍·회원 200명, 결제 실패율 0·웹훅 중복률 30%). 정상 A·B 성능 비교와 별도로 해석하며
--warmup·--failure-inject와 함께 쓰지 않습니다.
coupon-drop은 선착순(LIMITED) 쿠폰을 1만 VU가 동시에 받는 시나리오입니다(기본 100장, COUPON_QUANTITY로 변경). --coupon-strategy redis|db(기본 redis)는
core의 COUPON_LIMITED_CLAIM_STRATEGY로 전달되며, 측정 뒤 verify-coupon-integrity.sh로 초과 발급·중복·재고 일치를 검사합니다. --warmup·--aot-cache는 지원하지 않습니다.
--tool gatling은 queue-surge·hot-seats·seat-conflict-refund·coupon-drop을 지원하며 --aot-cache와 함께 쓰지 않습니다.
--booking-p95-ms는 Gatling 예매 p95 한도(양의 정수)입니다. 미지정 시 100명 이하 300ms, 초과 1500ms입니다. Gradle 없이 java를 직접 띄워 CPU 고정(--k6-cpus)·RSS 샘플링이 k6와 같은 조건이 되고,
JVM 힙은 --gatling-heap(기본 2g)으로 고정합니다. 기본은 리포트를 생략하며(-nr), --gatling-report로 HTML 리포트를 생성합니다.
리포트는 회차별 gatling-results/<실행 디렉터리>/index.html에 저장됩니다. 리포트 생성 시간·메모리도 측정에 포함됩니다.
SCOUTER_COLLECTOR_IP는 기본값 127.0.0.1입니다.
USAGE
  exit 1
}
need_value() { [[ $# -ge 2 && -n $2 ]] || { printf '%s 값이 필요합니다.\n' "$1" >&2; exit 1; }; }

parse_args() {
while (($#)); do
  case "$1" in
    --scenario) need_value "$@"; SCENARIO=$2; shift 2 ;;
    --runs) need_value "$@"; RUNS=$2; shift 2 ;;
    --vus) need_value "$@"; VUS=$2; shift 2 ;;
    --k6-cpus) need_value "$@"; K6_CPUS=$2; shift 2 ;;
    --app-cpus) need_value "$@"; APP_CPUS=$2; shift 2 ;;
    --failure-inject) FAILURE_INJECT=true; shift ;;
    --jfr) JFR=true; shift ;;
    --codelist) CODELIST=true; shift ;;
    --restart-after-warmup) need_value "$@"; RESTART_AFTER=$2; shift 2 ;;
    --jvm-opts) need_value "$@"; JVM_OPTS=$2; shift 2 ;;
    --observability) OBSERVABILITY=true; shift ;;
    --keep) KEEP=true; shift ;;
    --no-scouter) NO_SCOUTER=true; shift ;;
    --warmup) WARMUP=true; shift ;;
    --internal-warmup) INTERNAL_WARMUP=true; shift ;;
    --warmup-rounds) need_value "$@"; WARMUP_ROUNDS=$2; shift 2 ;;
    --no-rate-limit) NO_RATE_LIMIT=true; shift ;;
    --tool) need_value "$@"; TOOL=$2; shift 2 ;;
    --booking-p95-ms) need_value "$@"; BOOKING_P95_MS=$2; shift 2 ;;
    --gatling-heap) need_value "$@"; GATLING_HEAP=$2; shift 2 ;;
    --gatling-report) GATLING_REPORT=true; shift ;;
    --coupon-strategy) need_value "$@"; COUPON_STRATEGY=$2; shift 2 ;;
    --aot-cache) AOT_CACHE=true; shift ;;
    --aot-train-rounds) need_value "$@"; AOT_TRAIN_ROUNDS=$2; shift 2 ;;
    -h|--help) usage ;;
    *) printf '알 수 없는 옵션: %s\n' "$1" >&2; usage ;;
  esac
done
}

validate_args() {
case "$SCENARIO" in smoke|queue-surge|hot-seats|seat-conflict-refund|coupon-drop) ;; *) usage ;; esac
case "$COUPON_STRATEGY" in redis|db) ;; *) echo '--coupon-strategy에는 redis 또는 db가 필요합니다.' >&2; exit 1 ;; esac
COUPON_QUANTITY=${COUPON_QUANTITY:-100}
[[ $COUPON_QUANTITY =~ ^[1-9][0-9]*$ ]] || { echo 'COUPON_QUANTITY에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
if [[ $SCENARIO == coupon-drop ]] && { $WARMUP || $AOT_CACHE; }; then
  echo 'coupon-drop은 --warmup, --aot-cache와 함께 쓰지 않습니다.' >&2; exit 1
fi
if [[ $SCENARIO == seat-conflict-refund ]] && { $WARMUP || $FAILURE_INJECT || $AOT_CACHE; }; then
  echo 'seat-conflict-refund는 --warmup, --failure-inject, --aot-cache와 함께 쓰지 않습니다.' >&2; exit 1
fi
case "$TOOL" in k6|gatling) ;; *) echo '--tool에는 k6 또는 gatling이 필요합니다.' >&2; exit 1 ;; esac
if $GATLING_REPORT && [[ $TOOL != gatling ]]; then
  echo '--gatling-report는 --tool gatling과 함께 사용해야 합니다.' >&2; exit 1
fi
[[ $GATLING_HEAP =~ ^[1-9][0-9]*[mMgG]$ ]] || { echo '--gatling-heap에는 512m, 4g 같은 값이 필요합니다.' >&2; exit 1; }
if [[ $TOOL == gatling ]]; then
  [[ $SCENARIO == queue-surge || $SCENARIO == hot-seats || $SCENARIO == seat-conflict-refund || $SCENARIO == coupon-drop ]] || { echo '--tool gatling은 queue-surge, hot-seats, seat-conflict-refund, coupon-drop만 지원합니다.' >&2; exit 1; }
  $AOT_CACHE && { echo '--tool gatling은 --aot-cache와 함께 쓰지 않습니다.' >&2; exit 1; }
fi
if [[ -n $BOOKING_P95_MS ]]; then
  [[ $TOOL == gatling ]] || { echo '--booking-p95-ms는 Gatling 전용입니다.' >&2; exit 1; }
  [[ $BOOKING_P95_MS =~ ^[1-9][0-9]*$ && ${#BOOKING_P95_MS} -le 9 ]] || { echo '--booking-p95-ms에는 양의 정수가 필요합니다.' >&2; exit 1; }
fi
[[ -z $JVM_OPTS || $JVM_OPTS =~ ^-XX:[A-Za-z0-9_.=+:-]+( -XX:[A-Za-z0-9_.=+:-]+)*$ ]] || { echo '--jvm-opts에는 공백으로 구분한 -XX:옵션만 쓸 수 있습니다.' >&2; exit 1; }
[[ $RUNS =~ ^[1-9][0-9]*$ ]] || { echo '--runs에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
[[ $WARMUP_ROUNDS =~ ^[1-9][0-9]*$ ]] || { echo '--warmup-rounds에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
[[ $AOT_TRAIN_ROUNDS =~ ^[1-9][0-9]*$ ]] || { echo '--aot-train-rounds에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
[[ -z $RESTART_AFTER ]] || { $WARMUP || { echo '--restart-after-warmup은 --warmup이 필요합니다.' >&2; exit 1; }; [[ $RESTART_AFTER =~ ^(core|gateway|realtime)(,(core|gateway|realtime))*$ ]] || { echo '--restart-after-warmup은 core,gateway,realtime 중 쉼표로 구분한 값이어야 합니다.' >&2; exit 1; }; }
$AOT_CACHE && $WARMUP && { echo '--aot-cache와 --warmup은 함께 쓰지 않습니다(콜드 + AOT 조건).' >&2; exit 1; }
if $INTERNAL_WARMUP && { $WARMUP || $AOT_CACHE; }; then
  echo '--internal-warmup은 --warmup·--aot-cache와 함께 쓰지 않습니다.' >&2; exit 1
fi
[[ -z $VUS || $VUS =~ ^[1-9][0-9]*$ ]] || { echo '--vus에는 1 이상의 숫자가 필요합니다.' >&2; exit 1; }
[[ $SCENARIO != seat-conflict-refund || -z $VUS ]] || { echo 'seat-conflict-refund는 규모(100쌍)를 고정하므로 --vus를 쓰지 않습니다.' >&2; exit 1; }
}
