#!/usr/bin/env bash
# xk6-sse를 넣은 k6 바이너리를 build/loadtest/k6에 빌드한다. 사용: build-k6.sh [--force]
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
K6_VERSION=v2.3.0
XK6_SSE_VERSION=v0.2.0
XK6_IMAGE=grafana/xk6:1.5.0
OUT="$ROOT/build/loadtest"
FORCE=false
while (($#)); do
  case "$1" in
    --force) FORCE=true; shift ;;
    *) printf '알 수 없는 옵션: %s\n' "$1" >&2; exit 1 ;;
  esac
done
command -v docker >/dev/null || { echo '필요한 도구가 없습니다: docker' >&2; exit 1; }
mkdir -p "$OUT"
if ! $FORCE && [[ -x $OUT/k6 ]]; then
  version_output=$("$OUT/k6" version 2>&1 || true)
  if [[ $version_output == *"${K6_VERSION#v}"* && $version_output == *"xk6-sse"* && $version_output == *"$XK6_SSE_VERSION"* ]]; then
    echo "k6 ${K6_VERSION} + xk6-sse ${XK6_SSE_VERSION} 바이너리가 이미 있습니다."
    exit 0
  fi
fi
docker run --rm -u "$(id -u):$(id -g)" -e GOCACHE=/tmp/go-cache -e GOPATH=/tmp/go \
  -v "$OUT:/xk6" -w /xk6 "$XK6_IMAGE" \
  build "$K6_VERSION" --with "github.com/phymbert/xk6-sse@${XK6_SSE_VERSION}" --output /xk6/k6 \
  > "$OUT/build-k6.log" 2>&1 || { echo "k6 빌드 실패: $OUT/build-k6.log" >&2; tail -n 30 "$OUT/build-k6.log" >&2; exit 1; }
echo "k6 빌드 완료: $OUT/k6"
