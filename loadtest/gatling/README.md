# Gatling 부하 시나리오

`loadtest/scenarios`(k6)와 같은 흐름을 Gatling Kotlin DSL로 옮긴 모듈(`:loadtest-gatling`)이다. k6 스크립트와 `scripts/loadtest/run.sh`는 그대로 두며, 이 모듈은 Gateway(18000)를 HTTP로만 호출한다.

| Simulation | k6 대응 | 내용 |
|---|---|---|
| `SmokeSimulation` | `smoke.ts` | `GET /api/v1/performances` 확인 |
| `QueueSurgeSimulation` | `queue-surge.ts` | 대기열 동시 진입 → 입장 → 좌석 선택 → 예매 → 결제 → 확정 폴링 (기본 10,000명) |
| `HotSeatsSimulation` | `hot-seats.ts` | 입장한 사용자가 같은 시각에 앞줄 100석 경합 (기본 1,000명, 60초 뒤 시작) |
| `SeatConflictRefundSimulation` | `seat-conflict-refund.ts` | 좌석 충돌 환불 보상 검증 (`PHASE=first\|second`) |

`queue-ui.ts`(UI 확인용 가상 대기자 등록)는 옮기지 않았다.

## 실행

환경 변수 계약은 k6와 같다. 서비스와 시드(`scripts/loadtest/seed.sh`)가 준비된 상태에서 실행한다.

```bash
BASE_URL=http://localhost:18000 TICKETING_JWT_SECRET=... \
MEMBERS_FILE=build/loadtest/members.json SCHEDULE_FILE=<schedule.json> VUS=1000 \
./gradlew :loadtest-gatling:gatlingRun --simulation io.github.ticketing.loadtest.QueueSurgeSimulation --non-interactive
```

- `READINESS_FILE`: 시나리오 B·충돌 검증의 준비 현황 JSON 경로
- `PHASE`, `FIRST_FILE`: `SeatConflictRefundSimulation` 전용(k6와 같다). 사용자별 결과는 `PAIR_FIRST`·`PAIR_SECOND` 줄로 표준 출력에 남긴다.
- 리포트는 `loadtest/gatling/build/reports/gatling/`에 생긴다.

## run.sh로 실행 (메모리 비교)

```bash
./scripts/loadtest/run.sh --scenario queue-surge --tool gatling --gatling-heap 2g   # 기본 힙 2g
./scripts/loadtest/run.sh --scenario queue-surge                                      # k6 기준선
```

- 결과 디렉터리에 `gatling.log`, `gatling-summary.json`(k6 요약과 같은 형태), `gatling-gc.log`, `resources.csv`가 남는다. `resources.csv`의 3번째 열(부하 도구 RSS)과 실행 요약의 `부하 도구 최대 RSS`와 `호스트 메모리 최대 사용률`로 k6와 비교한다. 앱과 부하 도구를 한 PC에서 돌리므로 호스트 사용률이 실질 지표다(k6에서 90%에 도달한 원인). Gatling은 힙 2g 기준으로 측정하며, RSS는 힙 외 영역(메타스페이스, Netty direct 버퍼)만큼 힙보다 크다.
- 비교는 같은 `--k6-cpus`, 같은 `--vus`, 같은 시드에서 도구를 번갈아 가며 여러 번 돌린다.

## Gradle 없이 실행 (측정용)

메모리·CPU를 재려면 Gradle 데몬 없이 java를 직접 띄운다. 프로세스 PID가 곧 Gatling이라 `taskset`과 `sample-resources.py`에 그대로 쓸 수 있다.

```bash
./gradlew :loadtest-gatling:gatlingDist     # build/gatling-dist/lib 에 jar 모음 생성
mkdir -p build/loadtest/gatling-prefs/.java/.userPrefs
env BASE_URL=... TICKETING_JWT_SECRET=... MEMBERS_FILE=... SCHEDULE_FILE=... VUS=10000 \
  taskset -c 0-3 java -Xmx2g --enable-native-access=ALL-UNNAMED \
  -Djava.util.prefs.userRoot="$PWD/build/loadtest/gatling-prefs" \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -cp 'loadtest/gatling/build/gatling-dist/lib/*' io.gatling.app.Gatling \
  -s io.github.ticketing.loadtest.QueueSurgeSimulation -rf build/loadtest/gatling-results -nr
```

- `-Xmx`를 고정해야 RSS 비교가 의미 있다. 힙을 바꿔 가며 완주하는 최소 힙도 같이 본다.
- `-nr`은 종료 시 리포트 생성(로그 파싱 메모리)을 생략한다. 리포트는 나중에 `-ro <결과 디렉터리>`로 만든다. assertion은 `-nr`에서도 평가된다.
- `--add-opens`는 Gatling이 필요로 하며 `gatlingRun`은 플러그인이 대신 넣어 준다.
- `--enable-native-access`는 Netty의 네이티브 라이브러리 로드를 허용한다. `java.util.prefs.userRoot`는 쓰기 가능한 경로로 지정해 사용자 홈이 읽기 전용인 환경에서도 Preferences 잠금·저장 경고를 방지한다. `run.sh`는 회차별 `gatling-prefs/`를 사용한다.

## k6와 다른 점

- 사용자 정의 지표(`flow_*`, `booking_conflict`, `confirm_latency` 등)는 Gatling에 카운터가 없어 메모리로 집계해 종료 시 표준 출력으로 낸다. 요청별 응답 시간·성공률은 Gatling 리포트가 맡는다.
- k6의 threshold는 Gatling `assertions`로 옮겼다. booking_create p95(100명 이하 300ms, 초과 1,500ms), 전체 실패율 1% 미만, SSE 연결 성공률 99.9% 초과이며 미달해도 중단하지 않고 종료 코드로만 알린다.
- 409 SEAT_ALREADY_HELD·429는 k6처럼 예상 응답이다. 429는 `Retry-After`(최대 5초)만큼 쉬고 최대 5번 다시 보낸다. 다른 4xx·5xx는 KO다.
- SSE는 Gatling 내장 SSE 지원을 쓴다(xk6-sse 불필요). 메시지는 `{"event":"…","data":…}` JSON으로 체크에 전달된다.
- Gatling 기본 워밍업이 외부로 요청을 보내므로 끈다(`disableWarmUp`).
- `run.sh --tool gatling`으로 앱 기동·시드·서버 지표 차분·정합성 검증까지 k6와 같은 절차로 실행한다. queue-surge·hot-seats만 지원하며 `--warmup`·`--aot-cache`·seat-conflict-refund·smoke는 k6 전용이다.
