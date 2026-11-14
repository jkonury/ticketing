# M5-2 병목 개선과 전후 비교 계획

- 작성일: 2026-11-13
- 상태: 계획. 이 문서 작성으로 구현·측정이나 M5 완료를 처리하지 않는다.
- 선행 결과: [M5-1 기준 측정](../performance/m5-baseline.md), [로드맵 M5](../roadmap.md#m5-부하-테스트와-관측)
- 작성 기준: 로컬 계획 작성 지침(build/prompt.md)의 step 형식

## 범위와 판단 기준

M5-1에서 확인한 대기열 이탈·404, 인기 좌석 예매 지연, Gateway 오류를 재현하고 원인을 좁힌 뒤 개선한다. M6, web BFF 측정, 단계와 무관한 후속 항목은 포함하지 않는다. 구조 변경이나 버전 업그레이드를 개선의 전제로 삼지 않는다.

| 항목 | M5-1 관측값 | 계획에서의 처리 |
|---|---|---|
| A 대기열 이탈 | 예상 밖 오류율 중앙값 7.65%, 입장 속도 진단값 약 21명/초 | 입장·만료 제거·명시 이탈을 구분하고 생존 신호 지연과 스케줄러 실행 시간을 확인 |
| 등록 직후 404 | B 등록 404가 174~609건, 실제 예매 도달 인원 391~826명 | 재현 테스트와 서버 수정이 성능 튜닝보다 먼저. 부하 도달 인원을 함께 기록 |
| B 예매 생성 | k6 p95 중앙값 1,717.6ms, Hikari 획득 평균 약 160ms | 콜드 스타트와 워밍업을 분리하고, 연결 획득 경로를 확인한 뒤 코드·풀 설정을 각각 비교 |
| realtime 지연 | A 등록 서버 p95 중앙값 8,554ms, GC 정지 합계 중앙값 4.57초 | Redis 왕복·처리 지연과 GC 영향을 분리. 힙·폴링 설정을 한 번에 바꾸지 않음 |
| Gateway 구간 | A 티켓 발급 k6 약 3.4초 대 core 62ms, A-3에서 500 18건 | 기존 route 지표와 HTTP 연결 풀 지표를 수집하고 k6 호스트 자원과 대조 |
| Virtual Thread | 정상 상태 pinning 없음, submit 실패 0건 | 기존 결론을 보존하고 최종 JFR에서 재확인. pinning 제거 자체를 개선 목표로 잡지 않음 |
| 정합성 | 중복 판매 0건. 환불 보상 경로는 기준 부하에서 발생하지 않음 | 모든 실행에서 기존 검증 유지, 보상은 별도 통제 부하로 검증 |

위 수치는 현상이다. 클래스 로딩, Hikari 풀 크기, Lettuce 연결, heartbeat 지연, Gateway 연결 재사용 중 무엇이 주원인인지는 아직 확정하지 않는다. 서로 다른 요청 집합의 p95를 빼서 특정 구간의 p95로 해석하지 않는다.

## 실행 순서와 공통 검증

| Step | 이름 | 선행 결과 | 산출물 |
|---|---|---|---|
| 1 | `close-measurement-gaps` | M5-1 완료 | 대기열·Gateway·Hikari·k6 관측과 관측 보강 후 콜드 기준값 |
| 2 | `fix-queue-races` | Step 1 | 경합 재현·회귀 테스트와 대기열 정확성 수정 |
| 3 | `separate-warmup-measurements` | Step 2 | 워밍업 실행 모드, 구간 분리, 튜닝 전 비교 기준값 |
| 4 | `improve-queue-throughput` | Step 3 | A 대기열·realtime 개선과 전후 수치 |
| 5 | `reduce-booking-connection-wait` | Step 4 | B 예매 경로·Hikari 점검과 전후 수치 |
| 6 | `resolve-gateway-delays` | Step 5 | Gateway 지연·500 원인 확인과 필요한 수정 |
| 7 | `verify-compensation-under-load` | Step 6 | 중복 결제·웹훅 및 좌석 충돌 환불 검증 |
| 8 | `publish-m5-results` | Step 7 | 최종 비교 문서, JFR·풀 결론, 로드맵 갱신 |

각 step은 저장소와 [백엔드 지침](../rules/backend.md)을 따른다. 독립 세션에서 시작할 때 앞 step의 변경 경로·검증 결과·실행 디렉터리를 후술할 결과 문서에서 확인한다. 결과 문서는 Step 1에서 새로 만들 **docs/performance/m5-improvements.md**이며, 이후 step이 같은 문서에 근거와 진행 상태를 덧붙인다. M5-1 문서는 과거 기준으로 보존한다.

구현 검증은 다음 순서로 한다. 실제 변경 범위에 맞게 적용하고 같은 작업 디렉터리에서 Gradle 검사와 부하를 동시에 실행하지 않는다.

```bash
# Kotlin·Lua·백엔드 동작 변경: 필터 없이 실행
./scripts/agent/test/run-tests.sh
./scripts/agent/test/check-summary.sh ktlintCheck detekt
# core·gateway·realtime 흐름 변경
./scripts/e2e/run.sh
# 문서 제목·링크 변경
./scripts/agent/docs/check-docs.sh
```

스크립트 정적 검사는 `run-summary.sh -- bash -n <변경한 셸 파일들>`로 실행하고, 실행 동작은 아래 smoke·대상 시나리오로 확인한다. 문서만 변경한 step은 문서 검사만 한다. 모듈 의존을 바꿀 경우 Modulith 구조 검증과 `:core:modulithDocs`를 추가하지만, 현재 계획에는 의존 변경이 없다. web 변경이 필요해지면 범위를 먼저 재검토하고 해당 지침·검사를 적용한다.

## 측정 계약

1. 기본 경로는 기존처럼 k6 → Gateway다. A는 10,000명·5,000석·입장 배치 50명, B는 1,000명·핫 좌석 100석·입장 배치 1,000명을 유지한다. B의 60초 동시 시작 대기는 워밍업이 아니다.
2. M5-1과 직접 비교하는 실행은 앱을 매번 새로 띄우고 워밍업 없이 실행한다. CPU 분할, 힙, 추적 0.1, fake-pg 지연·실패율, scrape 5초를 기존값으로 고정한다. 바꾼 설정만 명시한다. Step 3의 워밍업 비교는 별도 계열이며 콜드 결과와 개선율을 섞지 않는다.
3. 성능 비교는 같은 관측·시나리오·설정으로 전후 각 3회 실행하고 모든 값과 중앙값을 남긴다. 진단 1회는 원인 확인용으로 구분한다. 변경 하나의 효과를 판정한 뒤 다음 변경을 적용하며 효과가 없거나 악화된 변경은 되돌린다. 실패·중단 실행과 재실행 사유도 보존한다.
4. 서버 수정 후 부하가 더 많이 도달할 수 있다. B는 등록·입장 완료·시작 시각 내 준비·예매 시도 인원을 기록한다. 지연 개선뿐 아니라 오류로 사라진 요청이 복구된 효과를 분리한다. 준비 인원이 1,000명에 못 미치거나 시작 시각을 넘긴 실행은 실패 근거로 남기고, 온전한 1,000명 동시 경합의 성능 합격 근거로 쓰지 않는다. A도 등록 → 입장 → 매진 이탈·예매 → 결제·확정의 인원 흐름을 남긴다.
5. 기존 오류 분류·재시도 수·threshold를 유지한다. 등록 404, `removed`, Gateway 500을 정상 처리로 바꾸거나 재시도로 숨겨 수치를 맞추지 않는다. 실패 주입·보상 시나리오만 기대 업무 결과를 별도로 정의한다.
6. 잠정 성능 기준은 예매 생성 p95 < 300ms, 예상 밖 오류율 < 1%, SSE 연결 성공률 > 99.9%다. 최종 문서에서 콜드 A·B 각각의 달성 여부와 워밍업 B의 달성 여부를 분리한다.
   - **기준 분리(2026-11-14 합의).** 진단 결과 B의 1,000명 동시 도착 p95는 앱 CPU 포화로 정해지며(코어 12개에서도 약 1.5~1.8초, [진단](../performance/m5-improvements.md)) 요청당 CPU를 한두 곳 줄여서는 움직이지 않았다. 대기열은 입장 배치로 동시 예매 인원을 제한하는 것이 설계다. 그래서 300ms 기준은 **입장 배치 규모의 동시 예매(B `--vus 50`, 콜드·워밍업 각 3회)**로 판정하고, 1,000명 동시 도착은 **스트레스 진단**으로 남긴다. 1,000명 조건에는 절대 기준(중복 판매 0건, 정합성, 오류율 < 1%)만 적용하고 p95 수치와 한계는 보고한다. 두 조건의 p95를 서로 비교하거나 한 표에 섞지 않는다. 중복 판매 0건과 DB·Redis 정합성은 매 실행의 절대 기준이다. 잠정 성능 기준 달성과 로드맵의 M5 완료 기준 충족은 구분하고, 미달 수치와 한계도 보고한다.
7. k6 이름별 지연은 실행 전체의 Trend 분포로 저장한다. 1초 p95의 중앙값을 실행 전체 p95와 비교하지 않는다. SSE 스트림 유지 시간을 API 지연으로 해석하지 않는다. RPS는 HTTP 요청 기준이며 입장 처리량·예매 처리량과 분리한다.
8. 환경·커밋과 작업 트리 상태·실제 설정·측정 구간·종료 코드·정합성 결과를 실행 디렉터리에 남긴다. 지표 누락은 0으로 채우지 않는다. 현재 runner는 threshold 미달(99)을 기록하고 성공 종료할 수 있으므로 셸 종료 코드 외에 k6 threshold 결과를 반드시 확인한다.

# Step 1: close-measurement-gaps

## 목표와 선행 조건

- 목표: 대기열 이탈과 연결 대기, 서버 밖 지연을 구분할 수 있는 관측을 확보한다.
- 선행 조건: M5-1 완료. Docker, `.env`, k6 바이너리, CPU 고정 도구와 기존 부하 실행 환경을 준비한다. 로컬 부하 전용 데이터만 사용한다.

## 참고

- [기준 측정](../performance/m5-baseline.md)의 「분석」, 「병목·결함 후보와 M5-2 제안」, 「한계」
- [ADR 0024](../adr/0024-observability-and-load-test.md), [Gateway 설정](../../gateway/src/main/resources/application.yml), [core 설정](../../core/src/main/resources/application.yml)
- `realtime/src/main/kotlin/io/github/ticketing/realtime/queue/`의 `QueueService`, `AdmissionService`, `QueuePositionStreamController`
- `scripts/loadtest/run.sh`, `scripts/loadtest/prom-snapshot.sh`, `observability/grafana/dashboards/ticketing-overview.json`, `loadtest/lib/metrics.ts`

## 작업

1. 대기열의 신규 등록·재등록·실제 입장·생존 만료 제거·명시 이탈을 결과별로 센다. Lua가 판정한 실제 변경 건수를 반환해 집계하고, 입장 후보 검사 수·배치 소요 시간·스케줄러 시작 간격·생존 신호 갱신 지연도 확인할 수 있게 한다. 만료 제거는 `seen` 없음과 유예 초과를 구분한다. 입장 토큰 조회 키 생성과 Gateway 검증 키 기록 완료 사이의 실패도 구분한다.
2. 회원·회차·예매 ID와 토큰을 지표 태그에 넣지 않는다. 지표는 제한된 결과·사유 태그를 사용하고 상세 재현에는 토큰을 뺀 로그와 trace를 쓴다. 여러 인스턴스의 합계와 중복 집계를 확인한다. 메트릭은 업무의 정합성 원장이 아니므로 프로세스 중단·Redis 응답 유실 시 계수 한계를 기록한다.
3. Gateway는 이미 metrics가 켜져 있다. `http.server.requests{uri="UNKNOWN"}`을 고치려고 사용자 경로 태그를 추가하기 전에 기존 `spring.cloud.gateway.requests`의 route·상태 태그와 히스토그램을 수집한다. HTTP 클라이언트 풀 지표도 켜서 active·idle·pending 및 획득 대기를 실제 노출 범위 안에서 기록한다. route `core-api`만으로 티켓 발급을 분리할 수 없으므로 k6 이름별 지연과 샘플 trace를 함께 본다.
4. Hikari 연결 획득 타이머의 실제 meter 이름을 확인하고 히스토그램을 추가한다. 기존 평균·최대·timeout·pending도 보존한다. 5초 게이지가 짧은 급증을 놓치는 한계는 타이머와 JFR로 보완하며, scrape 주기를 바꾼 진단은 별도로 구분한다.
5. runner에서 k6 프로세스 CPU·RSS와 호스트 CPU·메모리를 일정 간격으로 기록하고 종료 시 샘플러를 정리한다. CPU의 단위와 분모를 명시한다. k6의 예매·등록·티켓 발급 등 필요한 이름별 실행 전체 Trend를 summary에 남긴다. 기존 threshold·오류 집계는 보존한다.
6. 결과 문서를 만들고 지표 정의·실행 디렉터리·실패 이력·후속 step 상태를 기록한다. 관측 보강만 적용한 콜드 A·B를 각 3회 측정해 이후 수정의 비교 기준으로 쓴다. M5-1 대비 관측 비용·실제 부하 도달 인원 차이를 기록한다.

## 완료 기준과 검증

- 정상 입장, 만료 제거, 명시 이탈이 서로 다른 지표로 나타나고 고정 입력의 처리 수와 일치한다. 기존 동작은 보존되므로 M5-1의 threshold 미달이 남아도 그대로 기록한다.
- Gateway route별 타이머, Hikari 획득 버킷, 호스트·k6 자원과 이름별 k6 지연이 실행 산출물에 있다. 노출되지 않는 항목은 이유와 보완 수단을 남긴다.
- 공통 구현 검증 후, 다른 Gradle 작업이 끝난 상태에서 다음을 순차 실행한다.

```bash
./scripts/loadtest/run.sh --scenario smoke --observability
./scripts/loadtest/run.sh --scenario queue-surge --runs 3 --observability
./scripts/loadtest/run.sh --scenario hot-seats --runs 3 --observability
```

## 주의사항

- 관측을 보강하면서 admission 속도·presence 유예·풀·힙을 바꾸지 않는다. 새 지표가 생겼다는 사실과 성능이 나아졌다는 주장을 구분한다.

# Step 2: fix-queue-races

## 목표와 선행 조건

- 목표: 정상 등록·입장 중의 상태 전이가 404·잘못된 제거로 관측되지 않게 한다.
- 선행 조건: Step 1의 관측과 콜드 A·B 결과가 결과 문서에 있다.

## 참고

- [ADR 0021](../adr/0021-realtime-queue-and-sse-contracts.md), [대기열 계약](../architecture/flows.md#61-대기열과-입장), [Redis 키](../architecture/redis.md#대기열-키-형식-realtime)
- `realtime/src/main/kotlin/io/github/ticketing/realtime/queue/`와 `realtime/src/main/resources/redis/queue/admit.lua`
- `realtime/src/test/kotlin/io/github/ticketing/realtime/queue/`의 `QueueApiTests`, `AdmissionServiceTests`, `QueuePositionStreamTests` 및 기존 다중 인스턴스 테스트

## 작업

1. Testcontainers Redis·고정 Clock·코루틴 동기화로 실행 순서를 통제한다. 다음 경합을 각각 재현하고 현재 코드에서 기대 검사가 실패하는 근거를 남긴다. 대량 실행에서 우연히 발생하기만 기다리는 테스트로 끝내지 않는다.
   - `waiting` 등록 후 `seen` 기록 전에 admit가 실행된다.
   - 입장 토큰을 처음 확인한 뒤 순번을 읽기 전에 admit가 항목을 입장시킨다. 등록 응답·상태 조회·SSE 첫 조회·후속 폴링을 모두 살핀다.
   - Lua가 admitted 조회 키를 만들었지만 Gateway 검증용 키 기록은 아직 끝나지 않았다. 이때 상태 조회가 조회 키를 잘못 삭제하는지 확인한다.
   - 마지막 대기자를 처리하며 활성 회차를 제거하는 순간 새 등록이 들어온다. 대기자는 있는데 스케줄러 대상에서 빠지는지 확인한다.
2. 등록 순번 보존과 `waiting`·`seen` 기록을 같은 회차 해시 태그 안의 원자 연산으로 묶고, 조회가 입장 전이를 일관되게 판정하게 한다. 재등록은 순번을 바꾸지 않고 유효한 입장 상태를 유지한다. 활성 회차 등록·제거의 경합도 재현된 경우 함께 고친다.
3. 상태 조회는 미등록·명시 이탈·진짜 만료에 대한 기존 404를 보존한다. 이미 입장한 회원의 SSE는 기존 `admitted`를 보내고 종료한다. 처리 중 상태를 곧바로 없는 항목으로 판정하지 않는다. 토큰 본문·회원·회차 검증과 TTL은 그대로 유지한다.
4. 두 realtime 인스턴스가 같은 Redis를 쓸 때 재등록·입장 중복·조회·활성 회차 정리를 검증한다. 서비스 계층 재시도나 k6 재시도로 오류를 숨기지 않는다.
5. 콜드 A·B를 각각 3회 재측정하고 Step 1과 비교한다. B의 온전한 1,000명 도달과 실제 예매 시도 수를 먼저 확인한다. 인원이 복구되어 지연이 늘어도 이를 회귀와 단순 동일시하지 않는다.

## 완료 기준과 검증

- 재현 테스트가 수정 전 실패·수정 후 통과하고, 정상 등록·입장 과정의 `QUEUE_ENTRY_NOT_FOUND`와 잘못된 `removed`가 사라진다. 실제 미등록·이탈·만료 테스트는 계속 통과한다.
- 단일·다중 인스턴스 계약, Gateway 토큰 검증, E2E가 통과한다. 공통 구현 검증 뒤 아래를 순차 실행한다.

```bash
./scripts/loadtest/run.sh --scenario queue-surge --runs 3 --observability
./scripts/loadtest/run.sh --scenario hot-seats --runs 3 --observability
```

## 주의사항

- `queue:active`는 회차 해시 태그가 없고 Gateway용 admission 키도 회차 키와 슬롯이 다르다. 이 키들을 회차별 Lua에 무리하게 포함하지 않는다.
- ADR 0021이 허용한 프로세스 중단 시 토큰 기록 공백을 완전히 제거하는 새 프로토콜은 이번 step의 확정 범위가 아니다. 정상 동시 실행 결함과 구분한다. 기존 계약으로 해결할 수 없어 키 소유·발급 절차·API 상태를 바꿔야 한다면 재현 근거와 대안을 먼저 사용자에게 알리고, 합의 후 ADR과 아키텍처를 함께 고친다.

# Step 3: separate-warmup-measurements

## 목표와 선행 조건

- 목표: 콜드 스타트와 정상 상태의 인기 좌석 경합을 같은 코드에서 구분해 측정한다.
- 선행 조건: Step 2의 경합 수정·콜드 결과가 있고, B의 도달 인원 검증이 가능하다.

## 참고

- [기준 측정](../performance/m5-baseline.md)의 「Virtual Thread 점검」, 「시나리오 B의 예매 생성 지연과 Hikari 풀 대기」
- `scripts/loadtest/run.sh`, `scripts/loadtest/seed.sh`, `scripts/loadtest/prom-snapshot.sh`
- `loadtest/scenarios/hot-seats.ts`, `loadtest/lib/flow.ts`, `loadtest/lib/options.ts`, `loadtest/lib/summary.ts`

## 작업

1. runner에 명시적 `--warmup` 선택지를 추가한다. 기본은 기존 콜드 실행이다. 별도 회차로 등록 → 입장 → 예매 → 결제·웹훅·확정까지 워밍업한 뒤 비동기 처리가 완료됐는지 확인한다. 워밍업의 고정 요청 수·완료 조건·상한 시간을 결과 문서와 실행 설정에 기록한다. 튜닝 전후 동일하게 적용한다.
2. 워밍업 뒤 JVM을 재시작하지 않는다. 본 측정에는 새 회차·좌석과 별도 멱등 키를 사용하고, 워밍업 대기열·입장 토큰·이벤트가 본 부하에 영향을 주지 않게 정리·확인한다. DB 전체 삭제나 Redis 전체 flush로 격리를 대신하지 않는다.
3. 워밍업 k6 실행과 본 실행의 summary를 분리한다. 서버 지표는 본 부하 직전·종료 후의 직접 수집한 누적 카운터·히스토그램 버킷 차분으로 계산한다. 처음 생기는 시계열은 사전값 0, 기존 시계열은 사전값 차감, 중간 reset은 실행 오류로 표시한다. `last_over_time` 절대 누적값은 워밍업 계열에 그대로 쓰지 않는다. Hikari 평균·GC 합계·대기열 카운터도 같은 경계를 적용한다.
4. 워밍업 제외 여부는 요청 수·버킷 수의 통제 입력으로 검증한다. Prometheus 마지막 scrape 수집과 직접 수집 시점을 기록하고 수집 누락·reset을 검출한다. 게이지 최대값·타이머 max의 시간 창 한계는 별도 명시한다.
5. B의 준비 완료·시작 시각·늦은 시작·예매 시도 인원을 summary에 남긴다. 60초 대기를 워밍업으로 간주하거나 준비되지 않은 사용자를 조용히 제외하지 않는다. Step 2 콜드 B와 같은 서버 코드·설정으로 워밍업 B를 3회 측정해 이후 예매 튜닝의 기준으로 저장한다.

## 완료 기준과 검증

- 기본 명령의 콜드 동작이 보존되고 워밍업 회차의 요청·좌석·결제 수가 본 측정에 섞이지 않는다. 설정만 바꿔도 두 실행을 구분할 수 있다.
- 스크립트 정적 검사, 콜드·워밍업 smoke, 워밍업 B 3회 결과가 있다. 아래 `--warmup`은 이 step에서 구현할 인터페이스다.

```bash
./scripts/loadtest/run.sh --scenario smoke --observability
./scripts/loadtest/run.sh --scenario smoke --warmup --observability
./scripts/loadtest/run.sh --scenario hot-seats --warmup --runs 3 --observability
```

## 주의사항

- 워밍업만으로 줄어든 지연은 서버 코드 개선으로 쓰지 않는다. 워밍업을 넣었다는 이유로 잠정 threshold를 완화하지 않는다.

# Step 4: improve-queue-throughput

## 목표와 선행 조건

- 목표: A에서 정상 연결 사용자의 생존 만료 이탈과 등록 지연을 줄이고 실제 입장 처리량을 설명한다.
- 선행 조건: Step 3까지 측정 체계가 고정됐다. Step 2의 콜드 A를 이 step의 변경 전 기준으로 사용한다.

## 참고

- [기준 측정](../performance/m5-baseline.md)의 후보 1·4, [대기열 계약](../architecture/flows.md#61-대기열과-입장)
- `QueueService`, `AdmissionService`, `QueuePositionStreamController`, `QueueProperties`와 `admit.lua`
- `realtime/src/main/resources/application.yml`, `scripts/loadtest/run.sh`, Step 1의 결과 문서·관측값

## 작업

1. 등록 급증과 정상 상태를 나눠 `seen` 없는 제거·유예 초과 제거·heartbeat 지연·Redis 왕복 수와 지연·GC를 대조한다. 단일 Lettuce 연결이나 힙 부족을 확인 전 원인으로 단정하지 않는다.
2. 한 admission 주기의 실행 시간과 시작 간격을 확인한다. 현재 스케줄러는 `fixedDelay`이고 토큰별 외부 키 기록도 순차적이다. 배치 50명/설정 1초가 실제 50명/초를 보장하지 않는 점을 반영한다. 살아 있는 대기자가 충분할 때의 실측 처리량, 만료 후보 처리량, 회차별 지연을 기록한다.
3. 관측 근거에 따라 Redis 왕복 감소, 생존 신호 갱신 경로 조정, 배치의 제한된 처리 비용을 순서대로 검토한다. 같은 슬롯의 원자 연산과 구조화된 코루틴을 유지한다. 전체 대기열을 무제한 순회하는 Lua나 인스턴스 메모리만의 생존 상태를 도입하지 않는다.
4. GC 영향이 남을 때만 realtime 힙을 단독 변경해 비교한다. runner에 설정별 실행값과 JVM 옵션을 보존하는 입력을 추가하고 기본값을 유지한다. 폴링 주기·유예·입장량·힙을 동시에 바꾸지 않는다. 생존 유예를 늘릴 경우 연결이 끊긴 실제 이탈자가 정리되는 시간도 검증한다.
5. 효과가 있는 변경만 채택하고 콜드 A 3회를 같은 조건으로 측정한다. A의 등록 지연·오류율·SSE 성공률·입장량·불필요한 만료 제거·GC와 정합성을 전후 기록한다. 필요한 진단 실행은 기준값과 분리한다.

## 완료 기준과 검증

- 정상 연결자의 만료 제거 감소가 지표로 확인되고, 실제 이탈자의 유예 후 제거와 다중 인스턴스 입장량 제어는 유지된다. 바뀐 설정·남은 한계와 효과를 결과 문서에 남긴다.
- 공통 구현 검증 후 콜드 A를 실행한다. B 흐름에 영향을 주는 변경이면 B도 같은 조건으로 3회 비교한다.

```bash
./scripts/loadtest/run.sh --scenario queue-surge --runs 3 --observability
```

## 주의사항

- `removed` 이벤트의 의미를 바꾸거나 새 재진입 프로토콜을 도입하는 것은 확정 계획이 아니다. 계약·기본 설정 변경이 설계 문서와 달라진다면 적용 전에 알리고 합의한 뒤 관련 문서를 함께 갱신한다.

# Step 5: reduce-booking-connection-wait

## 목표와 선행 조건

- 목표: B의 예매 생성 지연에서 연결 획득·트랜잭션·첫 요청 비용을 구분하고 확인된 낭비를 줄인다.
- 선행 조건: Step 4 결과와 Step 3의 워밍업 B 기준값이 있다. Step 4가 B에 영향을 줬으면 그 상태의 비교 기준을 먼저 확보한다.

## 참고

- [기준 측정](../performance/m5-baseline.md)의 후보 2와 Hikari 표
- `core/src/main/kotlin/io/github/ticketing/booking/application/service/CreateBookingService.kt`, `PaymentResultService.kt`, `RequestPaymentService.kt`
- `core/src/main/kotlin/io/github/ticketing/booking/adapter/required/jpa/`의 `BookingPersistenceAdapter`, `ScheduleSeatPersistenceAdapter`
- `core/src/main/kotlin/io/github/ticketing/payment/application/service/PaymentTransactionService.kt`, `PaymentRequestService.kt`
- `core/src/test/kotlin/io/github/ticketing/booking/application/service/CreateBookingServiceTests.kt`, `core/src/test/kotlin/io/github/ticketing/booking/application/flow/BookingPaymentFlowTests.kt`, `core/src/main/resources/application.yml`

## 작업

1. 예매 생성의 기존 멱등 조회 → 좌석 사본 조회 → Redis 선점 → 저장 → 경합 시 멱등 재조회 경로별 연결 획득 횟수·점유 시간을 확인한다. M5-1의 총 획득 횟수에는 결제·이벤트·폴링도 섞여 있으므로 예매 요청 수로 나눈 값을 예매 한 건의 트랜잭션 수로 확정하지 않는다.
2. Redis와 PG 호출을 DB 트랜잭션 밖에 둔 구조를 보존하고 중복 조회·불필요한 획득이 확인된 부분만 줄인다. 선점 실패 시 동일 멱등 요청 복구, 저장 실패 시 자기 선점 해제, 부분 좌석 확정 롤백과 최종 유니크 제약을 회귀 검증한다. 조회를 합치려고 외부 호출 전체를 큰 트랜잭션으로 감싸지 않는다.
3. 코드 개선만으로 콜드·워밍업 B를 각각 3회 비교한다. 연결 획득 p95·평균·최대와 usage·pending·timeout, DB 연결 수·부하, 예매 지연을 함께 기록한다. 콜드에서만 느리면 정상 상태 풀 병목과 구분한다.
4. 대기가 남을 때만 PostgreSQL 연결 한도와 다른 앱·배치·관리 연결의 여유를 확인하고 풀 크기 10을 대조군으로 한 번에 하나의 후보값을 비교한다. 후보값·선택 이유·기각 이유를 결과 문서에 남긴다. 크기 증가는 확정 결정이 아니다. 점유 감소 없이 DB 병목을 키우는 변경은 되돌린다.

## 완료 기준과 검증

- 연결 대기의 발생 위치와 콜드·워밍업 차이가 설명되고 채택한 변경의 전후 각 3회 수치가 있다. 효과가 없으면 변경하지 않은 결론과 근거를 남긴다.
- 공통 구현 검증 후 아래를 순차 실행한다. Kotlin 변경 시 멱등·실패 복구·동시 확정 테스트를 포함한 전체 테스트와 E2E가 통과한다.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --runs 3 --observability
./scripts/loadtest/run.sh --scenario hot-seats --warmup --runs 3 --observability
```

## 주의사항

- 풀 크기로 동시 입장량이나 DB 유니크 제약을 대체하지 않는다. Modulith 리스너 안에서 중첩 새 트랜잭션을 만들어 추가 연결을 잡지 않는다.

# Step 6: resolve-gateway-delays

## 목표와 선행 조건

- 목표: A의 티켓 발급 지연과 Gateway 500을 발생기·Gateway·하위 앱으로 구분하고 확인된 원인을 개선한다.
- 선행 조건: Step 5 상태의 관측·부하 결과가 있다. 직전 변경으로 A에 영향이 있으면 그 상태의 콜드 A 비교 기준을 먼저 확보한다.

## 참고

- [기준 측정](../performance/m5-baseline.md)의 후보 5, [ADR 0020](../adr/0020-gateway-verification-contracts.md)
- `gateway/src/main/resources/application.yml`, `gateway/src/main/kotlin/io/github/ticketing/gateway/`, `scripts/loadtest/run.sh`
- Step 1의 route·클라이언트 풀·k6 CPU·RSS 지표와 실행별 Gateway 로그·샘플 trace

## 작업

1. A 10,000 VU에서 티켓 발급의 k6 지연·route 타이머·core 처리·클라이언트 풀 대기·GC·호스트 자원을 같은 시간축으로 본다. route 타이머에는 필터·라우팅이 포함되고 SSE route에는 연결 유지 시간이 들어갈 수 있음을 명시한다. 오류가 다시 나지 않으면 관측 횟수와 미재현 사실을 남기고 원인 해결을 단정하지 않는다.
2. `PrematureCloseException`·`IOException`의 발생 시점·하위 앱 종료 여부·연결 재사용과 idle 설정을 확인한다. 증거가 있을 때만 연결 설정을 한 항목씩 변경한다. SSE 장기 연결에 일반 API용 짧은 response timeout을 일괄 적용하지 않는다.
3. Gateway 힙·GC가 지연과 연결될 때만 힙 단독 변경을 비교한다. k6나 호스트가 포화한 경우 동일 앱 설정에서 발생기 CPU 배분을 바꾼 진단을 별도로 수행하고, 자원 배분이 다른 값을 서버 개선율로 쓰지 않는다. 변경 환경을 최종 기준으로 채택하려면 그 환경의 전후 기준을 모두 확보한다.
4. 채택한 변경은 콜드 A 3회로 비교하고 Gateway 500, 티켓 발급 지연, SSE 연결 성공률·오류율, 정합성을 기록한다. 연결 풀 계측 자체의 한계와 남은 원인은 명시한다.

## 완료 기준과 검증

- 지연 위치를 근거로 설명하거나 구분하지 못한 한계를 명확히 남긴다. 500을 재시도로 숨기지 않고 실제 서버 오류 수를 유지한다.
- 변경 시 공통 구현 검증·토큰·rate limit·SSE 재연결 회귀 확인 후 아래를 실행한다. 변경이 없으면 이미 확보한 같은 코드·설정의 실행 근거를 재사용한다.

```bash
./scripts/loadtest/run.sh --scenario queue-surge --runs 3 --observability
```

## 주의사항

- POST 티켓 발급·예매·결제에 포괄적 Retry 필터를 붙이지 않는다. 원인 미확정 상태에서 풀·idle·힙을 한 번에 바꾸지 않는다.

# Step 7: verify-compensation-under-load

## 목표와 선행 조건

- 목표: 정상 부하에서 가려진 DB 최종 좌석 방어선과 실제 환불 완료·멱등성을 별도 부하에서 검증한다.
- 선행 조건: Step 6까지 채택한 변경이 검증됐다. 부하 전용 회차와 회원·키를 구분할 수 있다.

## 참고

- [기준 측정](../performance/m5-baseline.md)의 「정합성」, [예매 시퀀스](../architecture/flows.md#621-예매-흐름-시퀀스-대기열-제외), [fake-pg 계약](../architecture/integrations.md)
- `BookingPaymentFlowTests`의 「다른 예매가 이미 sold_seat를 가진 좌석이면…」 테스트
- `PaymentResultService`, `core/src/main/kotlin/io/github/ticketing/payment/application/service/PaymentRefundService.kt`
- `scripts/loadtest/verify-integrity.sh`, `scripts/loadtest/run.sh`, `loadtest/scenarios/`, `loadtest/lib/`

## 작업

1. 기존 `hot-seats --failure-inject`를 별도 실행해 결제 실패·웹훅 중복을 다시 확인한다. 실제 실패·중복 전달이 발생했는지 건수로 남긴다. 단순히 설정률이 10%·30%라는 사실만으로 검증했다고 쓰지 않는다.
2. 새 `seat-conflict-refund` 시나리오를 runner·시드에 추가한다. 독립 좌석 그룹 100쌍(회원 200명)의 동시 처리로 시작하고 실행 manifest에 규모를 고정한다. 각 쌍에서 첫 PENDING 예매를 만든 뒤 **그 회차·좌석의 선점 키만** 유실시켜 일부 좌석이 겹치는 두 번째 PENDING을 만든다. 두 예매에 실제 API로 결제를 요청하고 fake-pg 웹훅을 처리해 DB 확정 경쟁을 일으킨다. 기존 통합 테스트의 유실 재현을 부하 실행으로 옮기며, 어느 예매가 이길지는 고정하지 않는다.
3. 각 쌍은 확정 1건, `SEAT_ALREADY_SOLD` 취소 1건, 패자 `sold_seat` 0건, 승자 SOLD 유지와 패자만의 좌석 반환을 검증한다. 패자의 결제는 실제 `REFUNDED`까지 기다리고 PG 거래의 환불 금액·상태와 맞춘다. 환불 요청 이벤트가 발행됐다는 사실만으로 완료 처리하지 않는다.
4. 보상 시나리오에서는 결제 실패율 0, 웹훅 중복률 30%를 사용해 좌석 충돌 환불과 결제 실패 취소를 구분한다. 같은 멱등 키의 결제 재요청·중복 웹훅에도 추가 확정·추가 환불이 없음을 검증한다. 보상 결과와 전달 중복 수를 기록하고, `unexpected_errors`의 정상 A·B 정의를 전역으로 바꾸지 않는다.
5. 기존 정합성 검증에 시나리오별 기대 결과·환불 검증을 더한다. booking·payment는 각 스키마에서 따로 조회해 외부 검증 도구에서 주문 ID로 대조하고, PG는 거래 조회 계약으로 확인한다. 스키마 간 조인·외래 키는 만들지 않는다. 비동기 환불에는 완료 조건과 상한 시간을 두고 미완료를 실패로 남긴다. 실패 시 각 원장과 Redis 상태를 보존한다.

## 완료 기준과 검증

- 기존 실패 주입 결과와 새 보상 부하 결과가 각각 있고, 보상 3회 모두 중복 판매·중복 환불·환불 미완료 0건이다. 기대한 충돌·환불 건수가 실제 발생해야 한다.
- 공통 구현 검증·스크립트 정적 검사 후 아래를 순차 실행한다. 새 시나리오 이름은 이 step에서 구현할 인터페이스다.

```bash
./scripts/loadtest/run.sh --scenario hot-seats --failure-inject --observability
./scripts/loadtest/run.sh --scenario seat-conflict-refund --runs 3 --observability
```

## 주의사항

- 선점 유실 주입은 부하 전용 회차·생성된 예매의 키에만 허용한다. DB 유니크 제약을 끄거나 판매 행을 조작하지 않는다. 테스트 편의용 운영 API를 만들지 않는다.
- 이 보상 시나리오는 Redis 유실을 인위적으로 주입한 정합성 검증이다. 정상 A·B 성능 비교와 별도 해석한다.

# Step 8: publish-m5-results

## 목표와 선행 조건

- 목표: 채택한 전체 변경의 성능·정합성과 Virtual Thread·풀 점검 결과를 문서화하고 실제 완료한 로드맵 항목을 갱신한다.
- 선행 조건: Step 7까지 완료. 최종 코드·설정과 비교 대상·실행 모드가 결과 문서에 명확히 기록됐다.

## 참고

- [로드맵 M5](../roadmap.md#m5-부하-테스트와-관측), [기준 측정](../performance/m5-baseline.md), [ADR 0024](../adr/0024-observability-and-load-test.md)
- 앞 step에서 누적한 docs/performance/m5-improvements.md와 실행 디렉터리의 k6 summary·Prometheus·정합성·자원·로그·JFR 산출물
- `docs/architecture.md`의 「개발 환경과 테스트」, `docs/runbook.md`, 저장소 명령 안내

## 작업

1. 최종 작업 트리에서 필요한 전체 검증을 확인한다. 이후 변경이 없는 동일 상태의 통과 결과는 재사용하고, 최종 통합으로 영향이 생긴 검사만 다시 실행한다. 모든 Gradle 검사가 끝난 뒤 아래 최종 부하를 순차 실행한다.
2. 콜드 A·B 각 3회, 워밍업 B 3회, A JFR 1회로 최종 수치를 확보한다. 보상 결과는 Step 7 이후 영향 있는 코드·설정 변경이 없으면 재사용한다. JFR은 M5-1처럼 추적을 끈 별도 실행으로 분리해 `jdk.VirtualThreadPinned`·`jdk.VirtualThreadSubmitFailed` 건수·최장·합계·스택·발생 구간을 기록한다. B의 첫 요청·풀 대기가 여전히 미해결이면 B JFR도 추가한다.
3. pinning은 기동·첫 요청·정상 상태로 나눠 평가하고 Hikari 크기·획득 분포·pending·timeout·PostgreSQL 연결 여유를 함께 결론 낸다. 정상 상태 pinning이 없으면 그 결과를 기록하며 불필요한 락 변경을 하지 않는다.
4. 결과 문서를 최종 보고서로 정리한다. 환경·버전·커밋·설정, 실행 모드·구간, 매 실행 수치와 중앙값, 도달 인원, 채택·기각한 변경, 원인 확인 수준, 각 threshold, 정합성·보상, 남은 한계를 담는다. M5-1 콜드 → 관측 보강 → 경합 수정 → 튜닝 후 계열과 워밍업 전후를 구분하고, 비교 조건이 달라진 지점에 단순 개선율을 붙이지 않는다.
5. 지속할 기본 설정이나 계약이 바뀌었다면 앞 step에서 합의·갱신한 ADR·아키텍처와 최종 코드가 맞는지 확인한다. 실행 선택지가 늘면 runbook·명령 안내도 갱신한다. 대용량 실행 산출물은 git에 추가하지 않는다.
6. 실제 개선과 전후 수치가 있으면 로드맵의 병목 개선 항목을, JFR·풀 결론이 있으면 Virtual Thread 점검 항목을, 최종 보고서가 완성되면 문서화 항목을 체크한다. 중복 판매 0건과 전후 문서 등 M5 완료 기준을 확인한 뒤 전체 상태를 갱신한다. 잠정 성능 기준 미달은 명시하고 해결된 것으로 표현하지 않는다. M6 항목은 시작하지 않는다.

## 완료 기준과 검증

- 최종 비교 표에 모든 실행값·중앙값과 기준 달성 여부가 있고, 모든 정합성 검사에서 중복 판매 0건이다. 보상 경로의 실제 환불 완료와 멱등성 근거가 있다.
- JFR과 커넥션 풀 점검 결론, 실패·미재현·남은 한계, 필요한 산출물 위치가 문서에 남는다. 로드맵 체크는 실제 완료 상태와 일치한다.

```bash
./scripts/agent/test/check-summary.sh
./scripts/loadtest/run.sh --scenario queue-surge --runs 3 --observability
./scripts/loadtest/run.sh --scenario hot-seats --runs 3 --observability
./scripts/loadtest/run.sh --scenario hot-seats --warmup --runs 3 --observability
./scripts/loadtest/run.sh --scenario queue-surge --jfr
./scripts/agent/docs/check-docs.sh
```

## 주의사항

- 콜드 기준이 미달인데 워밍업 결과만 제시하지 않는다. 측정값을 예상 개선 수치로 대체하지 않는다. 문서 작성·관측 추가·풀 크기 변경만으로 로드맵의 개선 완료를 체크하지 않는다.

## 공식 문서 확인 근거

2026-11-13 확인. 구현 시에는 version catalog와 실제 해석된 의존성을 기준으로 API·설정 이름을 다시 대조한다.

- [Spring Boot 4.1 Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html): Hikari 계측과 meter별 히스토그램 설정. 실제 meter 이름·버킷 노출을 확인한 뒤 적용한다.
- [Spring Cloud Gateway 5.0 Global Filters](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html): route별 요청 타이머와 태그. [설정 목록](https://docs.spring.io/spring-cloud-gateway/reference/configprops.html)에서 `spring.cloud.gateway.server.webflux.metrics.*`와 `httpclient.pool.*`를 확인했다. 로컬 5.0.4 소스·설정 메타데이터도 대조했으며 기존 프로젝트 설정은 metrics 활성화 상태다. 원시 path 태그는 추가하지 않는다.
- [HikariCP Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing): 연결 증설을 가정하지 않고 DB 자원과 실제 대기·처리량으로 후보를 비교한다.
- [Redis Lua scripting](https://redis.io/docs/latest/develop/programmability/eval-intro/): 원자 실행은 서버를 점유하므로 회차별 제한된 작업만 묶는다. 서로 다른 슬롯 키를 한 스크립트로 처리하는 전제로 설계하지 않는다.
