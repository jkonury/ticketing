# 0022. 최소 저장소 인터페이스와 테스트 실행 정책

- 상태: 수락
- 작성일: 2026-11-12
- 결정 주체: 사용자 요청 반영
- 관련: [ADR 0005](0005-jpa-write-jooq-read.md), [실행 계획](../plans/test-structure-improvement.md)

## 배경

Spring과 인프라를 사용하는 검증이 개발 중 반복 실행에 섞여 있고, 범용 저장소 인터페이스는 직접 작성한 테스트 fake에 사용하지 않는 메서드까지 요구한다. 빠른 피드백을 제공하면서 DB와 이벤트의 실제 보장을 검증해야 한다.

## 결정

- JPA 저장소는 `Repository<T, ID>`를 직접 상속하고 필요한 메서드만 선언한다. 공통 저장소와 직접 `EntityManager` 구현은 추가하지 않는다. JPA 쓰기·jOOQ 조회, 트랜잭션·잠금·flush·유니크 제약은 유지한다.
- 단위 테스트는 직접 만든 fake를 사용하며 Spring·외부 인프라·Mockito·MockK 없이 실행한다. DB 제약, 잠금, HTTP, 이벤트 전달, Redis와 Batch는 실제 통합 검증으로 남긴다.
- 단위와 통합·구조 검증을 분리하고 전체 `check`에 포함한다. 문서 생성은 명시적 작업으로 분리하고 별도 프로세스 E2E는 기존 경로를 유지한다. 개발 시 단위 테스트, Stop·pre-commit에서는 전체 검사를 실행한다.
- 컴파일을 제외한 전체 단위 테스트 JVM 실행 시간 합계가 연속 3회 각각 10초 이하가 목표다. 단계별 전환과 검증은 [실행 계획](../plans/test-structure-improvement.md)에 기록한다.

## 검토한 대안

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| `JpaRepository` 유지와 mock 사용 | 사용하지 않는 메서드까지 노출하고 테스트가 mock 프레임워크에 의존한다. |
| 공통 최소 저장소 | 저장소별 필요가 달라 공통 추상화가 불필요한 메서드를 다시 노출한다. |
| `EntityManager` 직접 구현 | 영속성 구현과 유지 비용이 증가한다. |
| 모든 검증을 단위 테스트로 대체 | 실제 DB 제약·잠금과 인프라 연동을 증명하지 못한다. |

## 결과와 검증

각 저장소는 호출하는 메서드만 드러내고 단위 테스트는 빠르게 격리된다. 대신 fake 구현과 실제 연동 검증을 함께 유지해야 한다. Spring Data는 `Repository`를 상속하고 기본 CRUD 메서드와 일치하는 서명을 선택적으로 선언하는 방식을 지원한다. `saveAndFlush` 등 JPA 전용 메서드의 실제 동작은 프로젝트 버전의 통합 테스트로 확인한다. 컨텍스트 설정과 프로세스가 다르면 캐시를 공유하지 않으므로 필요한 격리만 유지한다.

- [Spring Data 저장소 인터페이스](https://docs.spring.io/spring-data/jpa/reference/repositories/definition.html)
- [Spring 테스트 컨텍스트 캐시](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
- [Gradle JVM 테스트](https://docs.gradle.org/current/userguide/java_testing.html)
