# Step 8: publish-m5-results

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

- 목표: 채택한 전체 변경의 성능·정합성과 Virtual Thread·풀 점검 결과를 문서화하고 실제 완료한 로드맵 항목을 갱신한다.
- 선행 조건: Step 7까지 완료. 최종 코드·설정과 비교 대상·실행 모드가 결과 문서에 명확히 기록됐다.

## 참고

- [로드맵 M5](../../docs/roadmap.md#m5-부하-테스트와-관측), [기준 측정](../../docs/performance/m5-baseline.md), [ADR 0024](../../docs/adr/0024-observability-and-load-test.md)
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
