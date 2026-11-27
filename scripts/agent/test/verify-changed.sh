#!/usr/bin/env bash
# 변경된 경로에 맞는 검사를 골라 순차 실행하고 실패만 요약한다. E2E·부하·modulithDocs는 실행하지 않고 필요 여부만 알린다.
# 사용법: scripts/agent/test/verify-changed.sh [--dry-run] [기준 ref]
#   기준 ref가 없으면 HEAD 대비 작업 트리(스테이지·미추적 포함)를 본다. 예: main, HEAD~3
#   A..B 형식이면 두 커밋 사이 변경만 본다(위임 task 커밋 검증용). 예: HEAD~1..HEAD
#   --dry-run은 고른 검사만 출력한다.
# 종료 코드: 0 전부 통과(또는 검사 없음), 1 실패 있음, 2 인자·환경 오류
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)" || exit 2
cd "$ROOT" || exit 2

dry_run=0
base=HEAD
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=1 ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    -*) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
    *) base="$arg" ;;
  esac
done
if [[ "$base" == *..* ]]; then
  for ref in "${base%%..*}" "${base##*..}"; do
    git rev-parse --verify -q "$ref^{commit}" >/dev/null || { echo "ref를 찾을 수 없다: $ref" >&2; exit 2; }
  done
  mapfile -t files < <(git diff --name-only "$base" | sort -u)
else
  git rev-parse --verify -q "$base^{commit}" >/dev/null || { echo "기준 ref를 찾을 수 없다: $base" >&2; exit 2; }
  mapfile -t files < <({ git diff --name-only "$base"; git ls-files --others --exclude-standard; } | sort -u)
fi
if [ "${#files[@]}" -eq 0 ]; then
  echo "변경 없음 (기준: $base)"
  exit 0
fi

# Gradle 프로젝트 경로와 그 프로젝트에 의존하는 프로젝트(각 build.gradle.kts의 project(...) 기준)
declare -A GRADLE_PROJECT=(
  [common]=:common [contract]=:contract [core]=:core [core-batch]=:core-batch
  [payment]=:payment [payment-batch]=:payment-batch [realtime]=:realtime [gateway]=:gateway
  [fake-pg]=:fake-pg [e2e]=:e2e [loadtest/gatling]=:loadtest-gatling
)
declare -A DEPENDENTS=(
  [common]="core core-batch payment payment-batch realtime"
  [contract]="core core-batch payment payment-batch"
  [core]="core-batch"
  [payment]="payment-batch"
)

declare -A modules=()
full_check=0 web=0 web_build=0 api_contract=0 docs=0 skills=0 hook=0 loadtest_py=0 e2e=0 modulith=0
unmapped=() sh_files=()

for f in "${files[@]}"; do
  case "$f" in
    core/src/main/*|payment/src/main/*|realtime/src/main/*|docs/api/openapi/*|web/openapi-ts.config.ts|web/scripts/api-contracts.mjs|web/package.json|web/pnpm-lock.yaml|web/src/server/api/generated/*)
      api_contract=1 ;;
  esac
  case "$f" in
    build-logic/*|gradle/*|build.gradle.kts|settings.gradle.kts|gradle.properties|config/*) full_check=1 ;;
    loadtest/gatling/*) modules[loadtest/gatling]=1 ;;
    docs/api/openapi/core.json) modules[core]=1 ;;
    docs/api/openapi/payment.json) modules[payment]=1 ;;
    docs/api/openapi/realtime.json) modules[realtime]=1 ;;
    docs/*.md|*AGENTS.md|*CLAUDE.md|README.md) docs=1 ;;
    web/package.json|web/pnpm-lock.yaml|web/next.config.ts|web/tsconfig.json) web=1; web_build=1 ;;
    web/*) web=1 ;;
    .agents/skills/*|.claude/skills/*) skills=1 ;;
    scripts/agent/hook/*) hook=1 ;;
    scripts/loadtest/*.py) loadtest_py=1 ;;
    *.sh|.githooks/*) [ -f "$f" ] && sh_files+=("$f") ;;
    *)
      top="${f%%/*}"
      if [ "$top" != "$f" ] && [ -n "${GRADLE_PROJECT[$top]:-}" ]; then
        modules[$top]=1
        case "$f" in core/src/main/*) modulith=1 ;; esac
        case "$f" in */src/main/*) case "$top" in core|payment|realtime|gateway|fake-pg|contract) e2e=1 ;; esac ;; esac
      else
        unmapped+=("$f")
      fi
      ;;
  esac
done

for m in "${!modules[@]}"; do
  for d in ${DEPENDENTS[$m]:-}; do modules[$d]=1; done
done

gradle_tasks=()
if [ "$full_check" = 1 ]; then
  gradle_tasks=(check)
else
  for m in "${!modules[@]}"; do gradle_tasks+=("${GRADLE_PROJECT[$m]}:check"); done
  mapfile -t gradle_tasks < <(printf '%s\n' "${gradle_tasks[@]}" | sed '/^$/d' | sort)
fi

checks=()
[ "${#gradle_tasks[@]}" -gt 0 ] && checks+=("./scripts/agent/test/check-summary.sh ${gradle_tasks[*]}")
[ "$api_contract" = 1 ] && checks+=("pnpm --dir web run api:check")
if [ "$web" = 1 ]; then
  if [ "$web_build" = 1 ]; then checks+=("./scripts/agent/test/web-check.sh --build"); else checks+=("./scripts/agent/test/web-check.sh"); fi
fi
[ "$skills" = 1 ] && checks+=("./scripts/agent/skills/check-skills.sh" "./scripts/agent/docs/check-docs.sh .agents/skills")
[ "$docs" = 1 ] && checks+=("./scripts/agent/docs/check-docs.sh")
[ "$hook" = 1 ] && checks+=("python3 -m unittest discover -s scripts/agent/hook -p test_*.py -q")
for f in "${sh_files[@]}"; do checks+=("bash -n $f"); done
[ "$loadtest_py" = 1 ] && checks+=("python3 -m unittest discover -s scripts/loadtest -p test_*.py -q")

echo "변경 파일 ${#files[@]}개 (기준: $base)"
if [ "${#checks[@]}" -eq 0 ]; then
  echo "실행할 검사 없음"
else
  printf '검사: %s\n' "${checks[@]}"
fi
[ "$modulith" = 1 ] && echo "안내: core 모듈 의존을 바꿨다면 ./gradlew :core:modulithDocs로 docs/modulith/를 갱신한다."
[ "$e2e" = 1 ] && echo "안내: 서비스 흐름 변경의 전체 E2E는 PR의 CI에서 확인한다(docs/rules/backend-testing.md)."
if [ "${#unmapped[@]}" -gt 0 ]; then
  echo "자동 검사 대상이 아닌 변경 ${#unmapped[@]}개(직접 확인):"
  printf '  %s\n' "${unmapped[@]:0:10}"
  [ "${#unmapped[@]}" -gt 10 ] && echo "  ..."
fi

[ "$dry_run" = 1 ] && exit 0

failed=()
for c in "${checks[@]}"; do
  echo ""
  echo "## $c"
  # 검사 명령은 위에서 고정 문자열로 만든 것만 실행한다.
  read -r -a argv <<< "$c"
  "${argv[@]}" || failed+=("$c")
done

echo ""
if [ "${#failed[@]}" -gt 0 ]; then
  printf '실패한 검사: %s\n' "${failed[@]}"
  exit 1
fi
echo "선택된 검사 모두 통과"
