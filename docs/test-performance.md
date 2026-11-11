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

## 근거

- [Spring 테스트 생성자 주입](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/support-classes.html)
- [ApplicationEvents의 메서드 범위와 매개변수 주입](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/application-events.html)
- [Spring 컨텍스트 캐시 키](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
- [DynamicPropertyRegistrar](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/dynamic-property-sources.html)
- [Testcontainers 싱글턴 컨테이너](https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/)
