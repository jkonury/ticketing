# Step 1: close-measurement-gaps

## 독립 실행 지침과 인계

- 이 문서는 해당 step의 실행 지시서다. 이전 step이 완료되지 않았거나 결과·실행 산출물이 없으면 임의로 완료 처리하지 말고 누락과 차단 사유를 보고한다. 이전 step의 변경 경로·검증·실행 디렉터리는 `docs/performance/m5-improvements.md`에서 확인한다. Step 1이 이 문서를 생성한다.
- 저장소 루트에서 실행하며 기존 작업 트리와 사용자의 변경을 보존한다. 해당 영역을 수정하기 전에 `docs/rules/backend.md` 등 저장소 지침을 적용하고 필요한 코드·설계·ADR과 실제 의존성의 공식 문서를 확인한다. 설계와 다르게 가야 하면 변경 전에 사용자에게 알리고 합의 후 ADR·아키텍처를 함께 갱신한다.
- M6, web BFF 측정, 단계와 무관한 후속 항목은 범위 밖이다. 새 프로토콜·구조 변경이나 버전 업그레이드를 전제로 삼지 않는다. M5-1의 `docs/performance/m5-baseline.md`는 과거 기준으로 보존한다.
- 작업·검증·실패와 재실행 사유, 변경 경로, 채택·기각 근거와 모든 실행 디렉터리를 `docs/performance/m5-improvements.md`에 남겨 다음 step에 인계한다. 완료 기준이 미달이면 사실대로 보고하고 다음 step을 완료 처리하지 않는다. 로드맵 체크는 Step 8에서 실제 충족 여부에 따라 갱신한다.

## 공통 구현 검증

변경 범위에 맞게 다음을 순서대로 적용한다. Docker, `.env`, k6 바이너리와 CPU 고정 도구를 준비하고 로컬 부하 전용 데이터만 쓴다. 같은 작업 디렉터리에서 Gradle 검사와 부하 측정을 동시에 실행하지 않는다.

```bash
# Kotlin·Lua·백엔드 동작 변경: 필터 없이 실행
./scripts/agent/test/run-tests.sh
./scripts/agent/test/check-summary.sh ktlintCheck detekt
# core·gateway·realtime 흐름 변경
./scripts/e2e/run.sh
# 문서 제목·링크 변경
./scripts/agent/docs/check-docs.sh
```

셸 변경은 `./scripts/agent/test/run-summary.sh -- bash -n <변경한 셸 파일들>`로 정적 검사하고 해당 smoke·시나리오 실행으로 동작을 확인한다. 문서만 변경했다면 문서 검사만 한다. 모듈 의존을 변경하면 Modulith 구조 검증과 `./gradlew :core:modulithDocs`도 실행한다. web 변경이 필요해지면 먼저 범위를 재검토하고 관련 지침과 검사를 적용한다. 테스트 실패를 숨기거나 비활성화하지 않고 실패 이름과 메시지를 보고한다.

## 공통 측정 계약

1. 기본 경로는 기존처럼 k6 → Gateway다. A는 10,000명·5,000석·입장 배치 50명, B는 1,000명·핫 좌석 100석·입장 배치 1,000명을 유지한다. B의 60초 동시 시작 대기는 워밍업이 아니다.
2. M5-1과 직접 비교하는 실행은 앱을 매번 새로 띄우고 워밍업 없이 실행한다. CPU 분할, 힙, 추적 0.1, fake-pg 지연·실패율, scrape 5초를 기존값으로 고정한다. 바꾼 설정만 명시한다. Step 3의 워밍업 비교는 별도 계열이며 콜드 결과와 개선율을 섞지 않는다.
3. 성능 비교는 같은 관측·시나리오·설정으로 전후 각 3회 실행하고 모든 값과 중앙값을 남긴다. 진단 1회는 원인 확인용으로 구분한다. 변경 하나의 효과를 판정한 뒤 다음 변경을 적용하며 효과가 없거나 악화된 변경은 되돌린다. 실패·중단 실행과 재실행 사유도 보존한다.
4. 서버 수정 후 부하가 더 많이 도달할 수 있다. B는 등록·입장 완료·시작 시각 내 준비·예매 시도 인원을 기록한다. 지연 개선뿐 아니라 오류로 사라진 요청이 복구된 효과를 분리한다. 준비 인원이 1,000명에 못 미치거나 시작 시각을 넘긴 실행은 실패 근거로 남기고, 온전한 1,000명 동시 경합의 성능 합격 근거로 쓰지 않는다. A도 등록 → 입장 → 매진 이탈·예매 → 결제·확정의 인원 흐름을 남긴다.
5. 기존 오류 분류·재시도 수·threshold를 유지한다. 등록 404, `removed`, Gateway 500을 정상 처리로 바꾸거나 재시도로 숨겨 수치를 맞추지 않는다. 실패 주입·보상 시나리오만 기대 업무 결과를 별도로 정의한다.
6. 잠정 성능 기준은 예매 생성 p95 < 300ms, 예상 밖 오류율 < 1%, SSE 연결 성공률 > 99.9%다. 최종 문서에서 콜드 A·B 각각의 달성 여부와 워밍업 B의 달성 여부를 분리한다. 중복 판매 0건과 DB·Redis 정합성은 매 실행의 절대 기준이다. 잠정 성능 기준 달성과 로드맵의 M5 완료 기준 충족은 구분하고, 미달 수치와 한계도 보고한다.
7. k6 이름별 지연은 실행 전체의 Trend 분포로 저장한다. 1초 p95의 중앙값을 실행 전체 p95와 비교하지 않는다. SSE 스트림 유지 시간을 API 지연으로 해석하지 않는다. RPS는 HTTP 요청 기준이며 입장 처리량·예매 처리량과 분리한다.
8. 환경·커밋과 작업 트리 상태·실제 설정·측정 구간·종료 코드·정합성 결과를 실행 디렉터리에 남긴다. 지표 누락은 0으로 채우지 않는다. 현재 runner는 threshold 미달(99)을 기록하고 성공 종료할 수 있으므로 셸 종료 코드 외에 k6 threshold 결과를 반드시 확인한다.

## 목표와 선행 조건

- 목표: 대기열 이탈과 연결 대기, 서버 밖 지연을 구분할 수 있는 관측을 확보한다.
- 선행 조건: M5-1 완료. Docker, `.env`, k6 바이너리, CPU 고정 도구와 기존 부하 실행 환경을 준비한다. 로컬 부하 전용 데이터만 사용한다.

## 참고

- [기준 측정](../../docs/performance/m5-baseline.md)의 「분석」, 「병목·결함 후보와 M5-2 제안」, 「한계」
- [ADR 0024](../../docs/adr/0024-observability-and-load-test.md), [Gateway 설정](../../gateway/src/main/resources/application.yml), [core 설정](../../core/src/main/resources/application.yml)
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

## 공식 문서 확인 근거

2026-11-13 확인. 구현 시에는 version catalog와 실제 해석된 의존성을 기준으로 API·설정 이름을 다시 대조한다.

- [Spring Boot 4.1 Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html): Hikari 계측과 meter별 히스토그램 설정. 실제 meter 이름·버킷 노출을 확인한 뒤 적용한다.
- [Spring Cloud Gateway 5.0 Global Filters](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html): route별 요청 타이머와 태그. [설정 목록](https://docs.spring.io/spring-cloud-gateway/reference/configprops.html)에서 `spring.cloud.gateway.server.webflux.metrics.*`와 `httpclient.pool.*`를 확인했다. 로컬 5.0.4 소스·설정 메타데이터도 대조했으며 기존 프로젝트 설정은 metrics 활성화 상태다. 원시 path 태그는 추가하지 않는다.
- [HikariCP Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing): 연결 증설을 가정하지 않고 DB 자원과 실제 대기·처리량으로 후보를 비교한다.
- [Redis Lua scripting](https://redis.io/docs/latest/develop/programmability/eval-intro/): 원자 실행은 서버를 점유하므로 회차별 제한된 작업만 묶는다. 서로 다른 슬롯 키를 한 스크립트로 처리하는 전제로 설계하지 않는다.
