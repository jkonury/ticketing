# hot-seats 기동 시 내부 예열 계획

- 작성일: 2026-11-15
- 결정 근거: [ADR 0028](../adr/0028-internal-startup-warmup.md)
- 병행 계획(먼저 끝낸다): [Gatling 15회 외부 예열 계획](./hot-seats-gatling-p95-300ms.md), [인계](./hot-seats-gatling-handoff.md)
- 측정 도구: 위 계획과 같은 Gatling 러너(`--booking-p95-ms 300`, `verdict.json`)

## 목표와 판정

콜드(앱 새로 기동, 외부 워밍업 요청 없음, AOT 캐시 허용)에서 내부 예열을 포함한 기동 3회의 hot-seats 1,000명 예매 생성 p95 중앙값이 300ms 미만이고 3회 모두 달성한다.
정합성(중복 0·불일치 0)·오류율·SSE 성공률은 기존 판정 계약을 따른다. 순수 콜드 B는 회귀 기준(1,500ms)으로 함께 잰다.
외부 15회 예열 상태가 내부 예열의 목표치이므로, 그 값이 300ms 아래가 아니면 이 계획의 목표도 닿지 못한다. 두 계열의 수치를 섞어 개선율을 계산하지 않는다.

## 구현 현황 (측정 전)

| 항목 | 상태 | 위치 |
| --- | --- | --- |
| 판정 조건 재정의 | 문서화 | ADR 0028, `docs/architecture.md` |
| 러너 JVM 옵션 전달 | 구현, 미실행 | `run.sh --core-jvm-opts`·`--gateway-jvm-opts`, `env.txt`에 기록 |
| 러너 내부 예열 켜기 | 구현, 미실행 | `run.sh --internal-warmup`, core 로그의 `internal-warmup:` 줄을 `env.txt`로 옮김 |
| core 계층 1 예열 | 구현, 미실행 | `BookingWarmupRunner.warmRequestPath`, `JooqRenderWarmupAdapter` |
| core 계층 2 예열 | 구현, 단위 테스트 작성, 미실행 | `BookingWarmupService`, `RedisWarmupFixtureAdapter`, `BookingWarmupServiceTests` |
| readiness 게이트 | 구현, 미실행 | `WarmupHealthIndicator`(예열 중 `OUT_OF_SERVICE`) |
| Gateway 내부 예열 | **미구현** | 아래 "남은 일" 1 |

## 측정 서버에서 할 일 (순서)

1. 빌드·품질 검사: `./scripts/agent/test/check-summary.sh`, `./scripts/agent/test/check-summary.sh ktlintCheck detekt`, `./scripts/agent/test/run-tests.sh --tests '*BookingWarmupServiceTests'`, `./scripts/agent/test/run-tests.sh --tests '*HexagonalArchitectureTests'`.
   아직 한 번도 컴파일하지 않았다. 컴파일·ktlint·detekt 오류가 있으면 먼저 고친다.
2. 예열 통합 확인: `TICKETING_WARMUP_ENABLED=true`로 core를 띄워 (a) 예열 중 `/actuator/health`가 503이고 끝나면 UP, (b) 로그에 `internal-warmup:` 줄, (c) 예열 뒤 Redis에 `booking:*:{9000000000000000000…}` 키가 없음, (d) `booking.booking`에 `member_id < 0` 행이 없음을 확인한다. 이 항목은 `BookingWarmupIntegrationTests`(Testcontainers)로 자동화해 추가하는 것을 권장한다.
3. **1-a 컴파일 임계 대조(예열 코드 없이)**: `-XX:CompileThresholdScaling=0.1`·`0.25`, `-XX:CICompilerCount` 각각 콜드 B 3회. 예열 없이 줄어드는 폭을 기록한다.
   ```bash
   ./scripts/loadtest/run.sh --scenario hot-seats --tool gatling --gatling-heap 2g --runs 3 --observability --no-scouter \
     --booking-p95-ms 300 --k6-cpus 0-3 --app-cpus 4-15 --core-jvm-opts '-XX:CompileThresholdScaling=0.25' --gateway-jvm-opts '-XX:CompileThresholdScaling=0.25'
   ```
4. **1-b·1-c 내부 예열 3회**: 같은 명령에 `--internal-warmup`을 추가한다. JFR(`--jfr`)로 폭주 구간 Interpreted 비율이 15회 외부 예열 수준으로 내려갔는지, `env.txt`의 예열 중 DB·Redis 호출 수·소요 시간, `made not entrant`(deopt) 기록이 없는지 본다.
   기본 예열 크기(계층 1 30,000회, 계층 2 30회×48건)는 추측값이다. Interpreted 비율이 남으면 `TICKETING_WARMUP_LAYER1ITERATIONS`·`TICKETING_WARMUP_LAYER2ROUNDS`를 늘려 비교하고, 기동 시간 상한(health 대기 150초)을 함께 본다.
5. 15회 예열 중 p95가 1.2초로 튀는 원인이 병행 계획에서 풀리지 않았다면 JFR로 확인한다.
6. 검증·문서화: 순수 콜드 B 회귀 3회, 결과를 `docs/performance/m5/12-*.md`(신규)로 남기고 `docs/performance/m5-improvements.md` 허브와 로드맵에 연결한다. 판정 뒤에만 로드맵 항목을 체크하고 ADR 0028 상태를 수락 또는 기각으로 바꾼다.

## 남은 일 (이번 구현에서 하지 않은 것)

1. **Gateway 내부 예열.** JWT 검증, 필터의 파싱·키 생성, Jackson과 core가 ready된 뒤 예열 전용 경로로 소수만 호출하는 계층 2는 구현하지 않았다. core 예열만으로 폭주 구간 지연이 충분히 줄었는지 4번 결과를 보고 필요를 판단한다. 필요하면 WebFlux 규칙(블로킹 금지)에 맞춰 별도로 설계한다.
2. **Lettuce 명령 인코딩 단독 예열**은 계층 1에 넣지 않았다. 계층 2의 실제 Redis 호출이 같은 경로를 탄다.
3. **롤백 전용 한계.** 실제 커밋(JDBC commit) 경로와 커밋 후 동기화 콜백은 예열하지 못한다. 4번에서 `persist` 단계 Interpreted가 남으면 별도 방식(예열 전용 회원·회차로 실제 커밋 후 삭제)을 새 결정으로 검토한다.

## 설정 값 (환경 변수)

Spring Boot relaxed binding 규칙대로 속성 이름의 대시(`layer1-iterations`)는 환경 변수에서 제거한다. 적용 여부는 기동 로그의 `internal-warmup:` 줄 값으로 확인한다.

| 변수 | 기본 | 뜻 |
| --- | ---: | --- |
| `TICKETING_WARMUP_ENABLED` | `false` | 내부 예열 켜기. 러너 `--internal-warmup`이 설정한다 |
| `TICKETING_WARMUP_LAYER1ITERATIONS` | 30000 | 계층 1 반복 횟수 |
| `TICKETING_WARMUP_LAYER2ROUNDS` | 30 | 계층 2 회차 수 |
| `TICKETING_WARMUP_REQUESTSPERROUND` | 48 | 회차당 요청 수 |
| `TICKETING_WARMUP_LOSERSPERWINNER` | 15 | 승자 1건당 패자 수 |
| `TICKETING_WARMUP_CONCURRENCY` | 3 | 동시 요청 수 |
| `TICKETING_WARMUP_MAXREQUESTSPERSECOND` | 300 | 초당 상한 |
| `TICKETING_WARMUP_TIMELIMIT` | `40s` | 계층 2 시간 상한 |
