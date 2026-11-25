#!/usr/bin/env bash
# source용. 같은 작업 디렉터리의 Gradle 실행을 build/.agent-gradle.lock으로 직렬화한다.
# 사용: gradle_lock_prepare 후 ${GRADLE_PREFIX[@]+"${GRADLE_PREFIX[@]}"} ./gradlew ... 로 실행한다.
# - AGENT_GRADLE_LOCK_HELD=1이면 이미 락을 잡은 프로세스 안이므로 락을 건너뛴다(재진입).
# - flock이 없으면(macOS 기본) 경고 후 락 없이 실행한다.
# - flock -o로 락 fd를 Gradle 자식(데몬)에 넘기지 않아, 실행이 끝나면 락이 풀린다.

GRADLE_PREFIX=()

gradle_lock_prepare() {
  GRADLE_PREFIX=()
  [ "${AGENT_GRADLE_LOCK_HELD:-}" = "1" ] && return 0
  if ! command -v flock >/dev/null 2>&1; then
    echo "WARN: flock이 없어 Gradle 실행을 직렬화하지 않는다" >&2
    return 0
  fi
  local lock_file="build/.agent-gradle.lock"
  mkdir -p build
  if ! flock -n -o "$lock_file" true 2>/dev/null; then
    echo "다른 Gradle 실행 대기 중" >&2
  fi
  GRADLE_PREFIX=(flock -o "$lock_file" env AGENT_GRADLE_LOCK_HELD=1)
}
