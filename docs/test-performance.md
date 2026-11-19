# 통합 테스트 재사용 개선

측정일: 2026-11-11. 대상은 core 테스트 161개다.

## 변경

- 공통 베이스에 애플리케이션 클래스와 MockMvc 구성을 고정했다. 테스트마다 중첩 설정 자동 탐색 결과가 달라지거나 MockMvc 유무 때문에 컨텍스트가 분리되는 것을 줄인다. 테스트별 Clock, 가짜 PG, Mockito 대체, seed 프로필은 유지한다.
- PostgreSQL·Redis는 Spring 빈 대신 테스트 JVM의 싱글턴으로 시작한다. 컨텍스트가 닫혀도 공유 컨테이너가 종료되지 않으며, JVM 종료 후 Testcontainers의 Ryuk이 정리한다. Gradle 실행 간 재사용은 하지 않는다.
- 컨텍스트마다 PostgreSQL 데이터베이스와 Redis DB 번호를 따로 배정한다. 서로 다른 컨텍스트의 미완료 이벤트 재발행, 캐시, 선점 상태가 섞이지 않게 한다. 같은 컨텍스트의 테스트들은 기존처럼 고유 식별자로 데이터를 구분한다.

## 측정 방법

개선 전에는 생성자 주입 전환 커밋 `162d753`에서 전체 테스트를 실행했다. 개선 후에는 다른 Gradle 검사가 끝난 뒤 core 테스트를 단독 실행한다. 테스트 필터는 사용하지 않는다.

```bash
./scripts/agent/test/run-tests.sh :core:test --rerun
```

`core/build/test-results/test/TEST-*.xml`에서 testsuite의 `tests`와 `time`을 합산하고, `system-out`의 `Started … in … seconds`와 이미지별 `Creating container for image:` 로그를 센다. 컨테이너 수는 PostgreSQL·Redis만 포함하며 Ryuk과 jOOQ 코드 생성용 컨테이너는 제외한다.

소요 시간은 테스트 스위트 시간 합계이며 Gradle 전체 빌드 시간과 다르다. 전후 각 1회 로컬 측정이므로 실행 부하와 JVM 예열에 따라 달라질 수 있다. 컨텍스트·컨테이너 기동 수로 재사용 효과를 함께 확인한다.

## 측정 결과

| 지표 | 개선 전 | 개선 후 |
|---|---:|---:|
| 테스트 수 | 161 | 161 |
| 스위트 시간 합계 | 67.131초 | 52.752초 |
| Spring 컨텍스트 기동 | 9회 | 7회 |
| PostgreSQL 컨테이너 기동 | 9회 | 1회 |
| Redis 컨테이너 기동 | 9회 | 1회 |

스위트 시간 합계가 약 21.4% 줄었다. 별도 컨텍스트가 필요한 테스트의 데이터 격리는 유지한다.

## 단계 C: 컨텍스트 통합

측정일: 2026-11-12. 대상은 core 테스트 198개다. 같은 조건(`:core:test --rerun`)에서 통합 전 61.9초·컨텍스트 13회였다.

- Clock(`MutableClock`)·가짜 PG(`TestPg`, `TestPgTransactions`)·배치 실패 주입(`FailingExpireBookingUseCase`, `FailingDailySettlementWriter`)을 `CommonTestConfiguration`의 `@Primary` 공용 빈으로 올렸다. `IntegrationTestSupport`가 매 테스트 전에 초기화하고, 시각이 필요한 테스트는 `@BeforeEach`에서 `clock.now`를 설정한다.
- `batch` 프로필과 `chunk-size=1`을 베이스에 합쳤다. `batch` 프로필이 끄는 웹 환경은 `spring.main.web-application-type=servlet`으로 되살렸다. `BatchIntegrationTestSupport`는 없앴다.
- 결제·환불 리스너와 `PaymentResultListener`의 `@MockitoBean`을 제거했다. 공용 `TestPg`가 결제를 수락만 하고 웹훅은 보내지 않으므로 흐름이 이어지지 않는다.
- `ReconcileSeatSnapshotJobTests`는 잡이 모든 회차를 훑어 `changed` 합계에 다른 테스트의 회차가 섞이므로, 자기 회차의 Redis 상태로 단언하도록 바꿨다.

| 지표 | 통합 전 | 통합 후 |
|---|---:|---:|
| 테스트 수 | 198 | 198 |
| 스위트 시간 합계 | 61.9초 | 53.1~53.6초 (2회) |
| Spring 컨텍스트 기동 | 13회 | 5회 (기본 1, seed 1, `@ApplicationModuleTest` 3) |

## 로컬 core 병렬화 계획

2026-11-19 08:40 로컬 Gradle 프로파일(`build/reports/profile/profile-2026-11-19-08-40-39.html`, `test --rerun`) 기준이다.

| 지표 | 시간 |
|---|---:|
| 전체 경과 | 210.11초 |
| `:core:test` | 197.29초 |
| `:realtime:test` | 38.064초 |
| `:gateway:test` | 29.411초 |
| `:fake-pg:test` | 23.318초 |

`:core:test`의 소요 시간은 전체 경과의 93.9%에 해당한다. 프로젝트 간 병렬 실행이 이미 활성화되어 있고, `Task Execution`의 296.80초에는 서로 겹친 태스크 시간이 합산되어 있다. core 컴파일과 jOOQ 생성은 이 실행에서 `UP-TO-DATE`였다.

08:40 프로파일 이후의 별도 core XML에는 70개 스위트·374개 테스트·실패 0건이 기록되어 있다. 스위트 합계는 143.175초이고, Spring 기동 로그는 14건, 컨테이너 생성 로그는 PostgreSQL 1건·Redis 1건·Kafka 2건이다. 느린 스위트는 `ActuatorPrometheusTests`(19.386초), `PaymentListenerTests`(15.395초), `PaymentFlowTests`(12.805초), `KafkaRecoveryIntegrationTests`(10.696초) 순이다. 새 기준 측정에서는 같은 실행의 프로파일과 XML을 함께 보관한다.

1. **core 기준선 재측정.** 같은 커밋과 JDK·Docker 자원 한도에서 다른 Gradle 작업을 마친 뒤 `./scripts/agent/test/run-tests.sh :core:test --rerun --profile --no-configuration-cache`로 실행한다. 평소 설정의 경과 시간은 프로파일 없이 별도로 기록한다. Docker 이미지가 준비된 상태에서 각 설정을 순차로 3회씩 측정한다. 실행마다 프로파일·XML·로그를 별도로 보관하고 태스크 시간의 중앙값, 테스트 수·실패·건너뜀, Spring·컨테이너 기동 수, 호스트와 Docker의 최대 메모리 사용량을 비교한다.
2. **core에서 JVM 2개 비교.** `:core:test`에만 로컬 Gradle 속성(예: `-PcoreTestForks=2`)으로 `maxParallelForks`를 지정하고 기본값은 1로 둔다. 비교할 때 Gradle 워커 한도를 고정하고 2 이상을 확보한다. 각 JVM은 Spring 컨텍스트 캐시와 Testcontainers 싱글턴을 가지므로 두 JVM 모두 통합 테스트를 실행하면 기동 비용이 늘 수 있다. 고정 호스트 포트·파일·외부 서비스 등 JVM 밖 공유 자원을 사전에 확인한다. 같은 JVM의 테스트는 순차로 실행해 매 테스트 전 초기화하는 공용 Clock·가짜 PG·실패 주입 빈을 보호한다. [Gradle 9.8.2 Test DSL](https://docs.gradle.org/9.8.2/dsl/org.gradle.api.tasks.testing.Test.html#org.gradle.api.tasks.testing.Test:maxParallelForks)
3. **전체 재실행으로 채택 판단.** 2개 포크가 유리하면 동일한 조건에서 필터 없는 전체 `test --rerun`을 각 설정으로 3회 실행한다. 채택 기준은 전체 경과 중앙값 15% 이상 단축(210.11초 기준 약 179초 이하), 동일한 테스트 실행 목록과 성공·건너뜀 결과, OOM·스왑 증가·간헐 실패 없음이다. 실제 채택 판정에는 같은 커밋에서 새로 구한 1개 포크 기준선을 사용한다. 3개 포크는 2개 포크가 기준을 충족하고 CPU·메모리 여유가 있을 때만 추가 비교한다.
4. **이득이 작으면 비용이 큰 구간부터 줄인다.** 포크별 Spring·컨테이너 기동과 느린 테스트의 시작·종료를 비교한다. 기동이 지배적이면 불필요한 별도 Spring 컨텍스트부터 점검한다. 결제 재시도·브로커 복구 대기가 지배적이면 실패·복구·DLT 검증을 유지하면서 테스트용 backoff와 폴링 설정을 점검한다. 이후 같은 조건에서 다시 측정한다.

### 구현 및 측정 결과

`coreTestForks` 속성은 `:core:test`에 적용되며 기본값은 1이다. JVM 2개로 로컬 전체 테스트를 다시 실행하는 명령은 다음과 같다.

```bash
./scripts/agent/test/run-tests.sh test --rerun -PcoreTestForks=2
```

동일 작업 트리에서 JDK 25.0.5와 Gradle 워커 한도 8로 JVM 1개와 2개를 교대로 실행했다. core 단독 비교는 구성 캐시를 끄고 프로파일을 수집했다. 호스트 메모리와 스왑은 `/proc/meminfo`, Docker 메모리는 실행 중인 모든 Docker 컨테이너의 cgroup 사용량 합계에서 2초마다 표본을 수집했다.

| core 단독 측정 | JVM 1개 | JVM 2개 |
|---|---:|---:|
| `:core:test` 시간 (3회) | 175.83 / 144.82 / 188.63초 | 102.59 / 115.72 / 128.53초 |
| `:core:test` 중앙값 | 175.83초 | 115.72초 |
| 실행당 성공 테스트 | 374 | 374 |
| 실행당 Spring 기동 로그 | 14 | 16 |
| 실행당 PostgreSQL / Redis / Kafka 생성 로그 | 1 / 1 / 2 | 2 / 2 / 3 |
| 호스트 / Docker 최대 사용량 | 12,668 / 4,821 MiB | 13,780 / 5,269 MiB |
| 호스트 스왑 최대 증가량 (3회) | 42.2 / 0 / 0 MiB | 37.2 / 78.8 / 51.8 MiB |

core 태스크 중앙값은 34.2% 줄었다. 여섯 실행의 테스트 이름과 결과가 같았고 OOM은 없었다.

전체 재실행은 평소와 같이 구성 캐시를 켰다. 아래 경과 시간은 테스트 스크립트 호출부터 종료까지의 실측값이다.

| 전체 재실행 | JVM 1개 | JVM 2개 |
|---|---:|---:|
| 전체 경과 (3회) | 217.87 / 206.06 / 190.25초 | 122.33 / 119.97 / 119.44초 |
| 전체 경과 중앙값 | 206.06초 | 119.97초 |
| 실행당 성공 테스트 | 511 | 511 |
| 호스트 / Docker 최대 사용량 | 12,740 / 4,619 MiB | 13,933 / 5,063 MiB |
| 호스트 스왑 최대 증가량 (3회) | 0 / 0 / 0 MiB | 0 / 41.5 / 4.8 MiB |

전체 재실행 중앙값은 41.8%(86.09초) 줄었다. 여섯 실행의 테스트 이름과 결과가 같았고 OOM은 없었다. JVM 2개는 시간 단축 기준을 충족했다. 두 실행에서 호스트 스왑 증가가 관측되어 기본값은 1로 두고, JVM 2개는 로컬에서 명시적으로 선택한다. 이 측정에서 JVM 3개 비교는 생략했다.

실행별 결과와 원본 프로파일·테스트 XML은 로컬 `build/orchestration/core-test-forks/`에 보관했다.

## 근거

- [Spring 테스트 생성자 주입](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/support-classes.html)
- [ApplicationEvents의 메서드 범위와 매개변수 주입](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/application-events.html)
- [Spring 컨텍스트 캐시 키](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
- [DynamicPropertyRegistrar](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/dynamic-property-sources.html)
- [Testcontainers 싱글턴 컨테이너](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/)
