# Backend 리뷰 지침 (core, core-batch, realtime, gateway, fake-pg, common, contract)

백엔드 변경(diff, PR)을 리뷰할 때 쓰는 확인 순서와 판정 기준이다. [백엔드 공통 지침](backend.md)에서 변경 모듈의 작성 지침을 찾고, 테스트는 [백엔드 테스트 지침](backend-testing.md)을 적용한다. 설계 규칙은 [AGENTS.md 4절](../../AGENTS.md#4-반드시-지킬-설계-규칙)을 따른다. web 변경은 [frontend-review.md](frontend-review.md)를 따른다.

## 리뷰 방법

1. `git diff --stat`으로 범위를 확인하고 변경을 모듈(`core`의 어떤 모듈인지, `realtime`, `gateway` 등)과 계층(`domain`, `application`, `adapter`)으로 분류한다.
2. 아래 심각도 순서대로 확인한다. 위 단계에서 막히면 아래 단계는 지적만 짧게 남긴다.
3. 이번 변경이 건드리지 않은 기존 코드의 문제는 지적하지 않는다. 필요하면 후속 항목으로 제안한다.
4. 라이브러리 버전과 API(Spring Boot 4.x, Modulith, Spring Cloud Gateway, jOOQ)는 기억이 아니라 공식 문서로 확인한다.
5. 실행 결과를 근거로 삼는다. 테스트·검사는 `AGENTS.md` 7절의 스크립트로 실행하고, 실패는 실패한 그대로 보고한다.

## 심각도

| 등급 | 의미 | 처리 |
|---|---|---|
| 차단 | 설계 규칙 위반, 정합성·동시성 결함, 보안 결함, 데이터 손상 가능성 | 머지 전에 반드시 수정 |
| 주의 | 오류 처리·트랜잭션·테스트 누락, 규칙 이탈 | 수정 권장. 미루려면 사유 기록 |
| 제안 | 가독성, 이름, 중복 | 선택 |

지적은 `파일:행`, 해당 작성·테스트 지침의 절 또는 `AGENTS.md` 4절 번호, 구체적인 실패 시나리오를 함께 적는다. 취향 차이는 차단·주의로 올리지 않는다.

## 1. 차단 항목

### 설계 규칙 (`AGENTS.md` 4절)
- 코어 모듈이 다른 모듈의 내부 클래스를 직접 참조하지 않는가. 모듈 간 호출이 Modulith 이벤트로만 이뤄지는가.
- 모듈별 스키마를 넘는 외래 키·조인이 마이그레이션이나 jOOQ 쿼리에 없는가. `settlement`가 다른 모듈 스키마를 읽지 않는가.
- `realtime`과 `gateway`에 JPA, JDBC, jOOQ 의존이나 PostgreSQL 접근이 생기지 않았는가.
- 코어에 대기열 지식이 들어오지 않았는가. 입장 토큰 검증이 Gateway에 남아 있는가.
- 좌석 확정이 Redis 선점만 믿지 않고 DB `(회차, 좌석)` 유니크 제약을 최종 방어선으로 두는가. 위반이 "이미 판매됨"으로 변환되고 500으로 새지 않는가.
- 결제 요청과 웹훅 처리가 멱등 키로 중복을 걸러내는가.

### 동시성과 정합성
- Redis 좌석 선점이 Lua 스크립트로 원자적인가. 여러 명령으로 나눠 보내지 않는가.
- 선점 해제가 소유자 비교 후 삭제인가.
- DB 커밋 실패 시 Redis 선점을 되돌리는 경로와, TTL 누락을 Batch가 보정하는 경로가 고려됐는가.
- 트랜잭션 안에서 PG·HTTP 같은 외부 호출을 하지 않는가.
- 이벤트 리스너가 멱등한가. 같은 이벤트가 다시 와도 결과가 같은가.
- 같은 트랜잭션에서 JPA와 jOOQ를 섞을 때 flush 순서 문제(jOOQ가 flush 안 된 JPA 변경에 의존)가 없는가.

### 보안
- 인가를 URL 패턴·메서드 보안으로 서버에서 강제하는가. BFF·Gateway 통과를 신뢰하지 않는가.
- 리소스 소유자 확인(내 예매인가)이 헥사고날 모듈의 `application.service`나 계층형 모듈의 `service`에 있는가.
- 비밀번호, 토큰, 결제 정보가 로그·응답·예외 메시지에 남지 않는가.
- PG 웹훅 서명을 검증하는가.
- 비밀 값이 저장소에 들어가지 않았는가.

### 스키마
- 적용된 Flyway 마이그레이션 파일을 수정하지 않고 새 파일로 추가했는가. `ddl-auto`가 `validate`인가.
- 테이블이 해당 모듈의 스키마에 만들어졌는가.
- jOOQ 생성 코드를 수정하거나 커밋하지 않았는가.

### Virtual Thread와 WebFlux 경계
- `realtime`, `gateway`에 블로킹 호출(`runBlocking`, `Thread.sleep`, 블로킹 Redis/HTTP 클라이언트)이 없는가.
- `GlobalScope`를 쓰지 않고 `CancellationException`을 삼키지 않는가.
- `realtime`이 인스턴스 메모리에만 있는 상태에 의존하지 않는가.

## 2. 주의 항목

### 모듈·헥사고날 구조

[core 작성 지침](backend-core.md#코어-모듈-구조-modulith) · [contract 작성 지침](backend-contract.md)

- 모듈 공개 범위가 이벤트 클래스와 최소 API 타입뿐인가. 나머지가 하위 패키지에 있는가.
- 이벤트가 불변이고 과거형 이름이며 ID와 필요한 값만 담는가. 엔티티를 싣지 않았는가.
- 헥사고날 모듈에서 `adapter → application → domain` 방향이 지켜지는가. `domain`에 Spring, JPA, jOOQ import가 없는가.
- 컨트롤러가 `port.provided`만 호출하고 도메인 타입을 응답으로 그대로 내보내지 않는가.
- 비즈니스 규칙이 `domain`에 있고 서비스는 흐름 조율만 하는가.
- 모듈 의존을 바꿨다면 Modulith 검증 테스트를 돌렸고 `./gradlew :core:modulithDocs`로 다이어그램을 갱신해 함께 변경했는가.

### 영속성

[core 영속성 지침](backend-core.md#영속성)

- 조회가 jOOQ이고 조회 전용 DTO로 직접 매핑되는가. 조회용 쿼리를 JPA 연관 탐색으로 만들지 않았는가.
- JPA를 `JpaRepository`로만 쓰는가. `EntityManager` 직접 주입, `createNativeQuery`, `nativeQuery = true`, `JdbcTemplate` 문자열 쿼리가 없는가.
- 연관관계가 같은 애그리거트 안에서만, `LAZY`로 쓰이는가. 다른 애그리거트·모듈은 ID로 참조하는가.
- 금액이 `BigDecimal`/`numeric`, 시각이 `Instant`/`timestamptz`이고 현재 시각을 주입된 `Clock`에서 얻는가.
- 유스케이스의 트랜잭션 경계가 헥사고날 모듈의 `application.service`나 계층형 모듈의 `service`에 있고 조회는 `readOnly = true`인가.

### Kotlin과 엔티티

[공통 Kotlin 지침](backend.md#kotlin) · [core JPA 엔티티 지침](backend-core.md#jpa-엔티티)

- `!!`가 없는가. ID가 `@JvmInline value class`로 감싸져 있는가. 상태 분기에 `else` 없는 `when`을 쓰는가.
- JPA 엔티티가 `data class`가 아니고 `equals`/`hashCode`가 규칙대로인가. 영속 속성이 `var`(도메인 패키지는 `protected set`)인가.
- 엔티티가 내부 컬렉션을 그대로 반환하지 않는가.
- 요청 DTO의 검증 애노테이션에 `@field:`가 붙었는가.
- 불변식 실패 종류가 맞는가(`require`/`check`/`DomainException`). `IllegalStateException`·`IllegalArgumentException`이 API 경계까지 올라가 500이 되지 않는가.

### API

[core API 지침](backend-core.md#api) · [API 규약](../architecture/api-conventions.md)

- 경로가 `/api/v1/...` 복수형 명사인가.
- 오류가 `ProblemDetail`이고 도메인 오류에 안정적인 `code`가 있는가. 예외 변환이 `@RestControllerAdvice` 한 곳에서 이뤄지는가.
- 입력 검증은 경계에서 Bean Validation, 도메인 불변식은 도메인에서 하는가.
- 목록 조회가 커서 기반인가. 재시도될 수 있는 쓰기 API가 `Idempotency-Key`를 받는가.
- API 계약(요청·응답, 오류 `code`)을 바꿨다면 BFF·Gateway·`docs/architecture/integrations.md`와 맞는가. 호환을 깨는 변경이 합의됐는가.

### realtime과 Gateway

[realtime 작성 지침](backend-realtime.md) · [gateway 작성 지침](backend-gateway.md)

- `realtime`은 코루틴으로, Gateway 필터는 Reactor 계약에 맞춰 작성했는가.
- SSE에 heartbeat가 있는가. 좌석 SSE는 Stream ID로 재연결하고 순번 SSE는 현재 상태를 보내는가.
- 연결 종료 시 구독·리더 참조를 해제하고, 대기자는 마지막 신호와 유예 시간을 기준으로 정리하는가.
- Gateway 필터의 변경이 검증·rate limit·라우팅 책임에 해당하는가. 입장 토큰 검증 실패가 대기열 복귀용 오류 코드를 주는가.

### Batch

[Batch Job 지침](backend-core.md#batch-job) · [core-batch 작성 지침](backend-core-batch.md)

- Job별 파라미터와 쓰기 방식이 재시작·재실행의 정합성을 보장하는가.
- 대량 읽기에서 jOOQ reader와 chunk 처리로 메모리 사용량을 제한하는가.
- Job이 소유 모듈에 있는가([ADR 0019](../adr/0019-batch-jobs-in-owning-modules.md)).

### Virtual Thread

[Virtual Thread 공통 지침](backend.md#virtual-thread)

- 블로킹 I/O를 감싸는 `synchronized` 대신 `ReentrantLock`인가. `ThreadLocal`에 큰 객체가 없는가. 스레드 풀로 동시성을 제한하지 않는가.

### 로깅
- SLF4J 파라미터 방식인가. 예외를 로그만 남기고 삼키지 않는가. 상태 전이·결제 결과 로그에 `bookingId`, `paymentId`가 있는가.

### 테스트

[백엔드 테스트 지침](backend-testing.md)

- 변경한 동작에 맞는 테스트가 있는가: 도메인 상태 전이의 허용·금지 경로, 유스케이스(fake 포트), 어댑터(Testcontainers), 모듈(`Scenario`/`PublishedEvents`).
- 경쟁이 있는 변경에는 실제 병렬 실행으로 대상 불변식을 단언하는 테스트가 있는가. 좌석 확정 경쟁에서는 "성공 1건, DB 확정 1건"을 확인하는가.
- 이름이 한국어 백틱 문장이고, 시간은 `Clock` 고정, 코루틴은 `runTest`인가. `Thread.sleep`으로 기다리지 않는가.
- AssertJ로 통일했고, 도메인 상태를 리플렉션·랜덤 생성으로 주입하지 않았는가.
- 코어 통합 테스트가 `IntegrationTestSupport`를 재사용하고 컨텍스트·컨테이너를 불필요하게 늘리지 않는가(클래스별 `@Import`, 프로필, `@MockitoBean`, `@DirtiesContext`, 새 컨테이너).
- 테스트를 지우거나 `@Disabled`로 통과시키지 않았는가.

## 3. 제안 항목

- **가독성**: 약어 변수명, given/when/then 빈 줄, 논리 단위 빈 줄, 한 줄 다중 문장, 엔티티 애노테이션 줄 배치.
- **중복**: 두 클래스 이상이 쓰는 객체 생성 코드는 `XxxFixture`로 모았는가.
- **주석**: "왜"가 코드에 드러나지 않을 때만 있는가.
- **빌드**: 의존성 버전이 `gradle/libs.versions.toml`에만 있고 공통 설정이 convention plugin에 있는가. `@Suppress`나 `detekt-baseline.xml` 항목을 합의 없이 추가하지 않았는가.

## 4. 문서 정합

- 설계와 다르게 구현했다면 `docs/architecture.md` 변경 합의가 있었는가. 문서와 코드가 어긋난 채 남지 않았는가.
- 설계를 바꾸는 결정이면 새 ADR이 있는가.
- 단계 항목을 끝낸 변경이면 `docs/roadmap.md` 체크박스가 갱신됐는가. "단계와 무관한 후속 항목"을 요청 없이 앞당겨 구현하지 않았는가.
- 명령, 포트, 환경 변수가 바뀌면 `AGENTS.md`와 관련 문서가 따라왔는가.

## 5. 검증 확인

- `./scripts/agent/test/run-tests.sh`가 통과했는가(필터는 사용자가 지정한 경우만).
- Kotlin 변경 후 `ktlintCheck detekt`를 통과했는가.
- 모듈 의존을 바꿨다면 Modulith 구조 검증을 돌렸는가.
- core·gateway·realtime 흐름을 바꿨다면 `./scripts/e2e/run.sh`를 실행했는가.
- 성능에 민감한 변경(좌석 선점, 대기열, 부하 경로)이면 `scripts/loadtest/run.sh` 결과를 확인했는가. 다른 Gradle 작업과 동시에 돌리지 않았는가.
- 문서를 옮기거나 제목·링크를 바꿨다면 `./scripts/agent/docs/check-docs.sh`를 통과했는가.

## 리뷰 결과 형식

```
## 요약
변경 범위와 전반적 판단 한 줄.

## 차단
- `core/src/...:행` (해당 지침의 파일·절 또는 AGENTS.md 4절 N번): 문제와 실패 시나리오. 제안 수정.

## 주의
- ...

## 제안
- ...

## 검증
- run-tests: 통과/실패(실패한 테스트 이름과 메시지 일부)
- 실행하지 않은 검사와 사유
```

지적이 없는 등급은 "없음"으로 적는다. 확인하지 못한 항목은 통과로 쓰지 않고 "확인 안 함"으로 적는다.
