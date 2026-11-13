#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
usage() {
  cat <<'HELP'
사용법: ./scripts/dev/run.sh [--all] [--infra] [--observability] [--core] [--realtime] [--gateway] [--web]
옵션 없이 실행하면 전체 개발 스택을 기동합니다.
--infra: PostgreSQL, Redis, fake-pg  --observability: Prometheus, Tempo, Grafana
앱 옵션은 조합할 수 있습니다. 의존 인프라는 별도로 선택해야 합니다.
앱은 이 터미널에서 관리하며 Ctrl+C로 종료합니다. Docker 컨테이너는 유지됩니다.
HELP
}
infra=false; observability=false; core=false; realtime=false; gateway=false; web=false
if (($# == 0)); then set -- --all; fi
for arg in "$@"; do
  case "$arg" in
    --all) infra=true; core=true; realtime=true; gateway=true; web=true ;;
    --infra) infra=true ;;
    --observability) observability=true ;;
    --core) core=true ;;
    --realtime) realtime=true ;;
    --gateway) gateway=true ;;
    --web) web=true ;;
    -h|--help) usage; exit 0 ;;
    *) printf '알 수 없는 옵션: %s\n' "$arg" >&2; usage >&2; exit 1 ;;
  esac
done
if $infra || $observability || $core || $gateway || $realtime; then
  [[ -f .env ]] || { echo '.env가 필요합니다. cp .env.example .env 후 값을 설정하세요.' >&2; exit 1; }
fi
if $web; then
  command -v pnpm >/dev/null || { echo 'pnpm이 필요합니다.' >&2; exit 1; }
  [[ -f web/.env.local ]] || { echo 'web/.env.local이 필요합니다. web/.env.example을 복사해 설정하세요.' >&2; exit 1; }
  [[ -d web/node_modules ]] || { echo 'web 의존성이 없습니다. cd web && pnpm install을 실행하세요.' >&2; exit 1; }
fi
pids=()
cleanup() {
  trap - EXIT INT TERM
  if ((${#pids[@]})); then
    for pid in "${pids[@]}"; do kill -- "-$pid" 2>/dev/null || true; done
    wait || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
tasks=()
$infra && tasks+=(:fake-pg:bootJar)
$core && tasks+=(:core:bootJar)
$realtime && tasks+=(:realtime:bootJar)
$gateway && tasks+=(:gateway:bootJar)
if ((${#tasks[@]})); then ./gradlew "${tasks[@]}"; fi
if $infra; then docker compose up -d --build --wait; fi
if $observability; then docker compose -f compose.yaml -f compose.observability.yaml up -d --wait prometheus tempo grafana; fi
start() {
  local name=$1
  shift
  mkdir -p build/dev/logs
  setsid "$@" > "build/dev/logs/$name.log" 2>&1 &
  pids+=("$!")
  printf '%s 실행 중 (로그: build/dev/logs/%s.log)\n' "$name" "$name"
}
$core && start core java -jar core/build/libs/core-0.0.1-SNAPSHOT.jar
$realtime && start realtime java -jar realtime/build/libs/realtime-0.0.1-SNAPSHOT.jar
$gateway && start gateway java -jar gateway/build/libs/gateway-0.0.1-SNAPSHOT.jar
$web && start web bash -c 'cd "$1/web" && exec pnpm dev' _ "$ROOT"
if ((${#pids[@]})); then
  echo '앱 종료: Ctrl+C'
  set +e
  wait -n "${pids[@]}"
  status=$?
  set -e
  echo '앱 프로세스가 종료되어 나머지 앱을 정리합니다.' >&2
  ((status != 0)) || status=1
  exit "$status"
fi
