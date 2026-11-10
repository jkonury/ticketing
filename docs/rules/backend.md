# Backend 지침

Kotlin, Spring Boot 4.x, Java 25. 설계 근거는 `docs/design.md`.

## 빌드

- Gradle Kotlin DSL. 의존성 버전은 `gradle/libs.versions.toml`(version catalog)에만 적는다.
- 공통 빌드 설정은 convention plugin으로 모으고, 각 모듈의 `build.gradle.kts`에는 그 모듈 고유의 것만 둔다.
- `realtime`과 `gateway`에는 JPA, JDBC, jOOQ 의존성을 추가하지 않는다.

## Kotlin

- `!!`를 쓰지 않는다. null은 타입과 `?:`, `requireNotNull`, `checkNotNull`로 다룬다.
- 도메인 모델은 `val` 우선. 상태 변경은 의미 있는 이름의 메서드로만 한다 (`booking.confirm()`), setter를 노출하지 않는다.
- 식별자는 `@JvmInline value class`로 감싼다 (`BookingId`, `SeatId`, `MemberId`). 서로 다른 ID를 `Long`으로 섞어 쓰지 않는다.
- 상태와 결과의 분기는 `sealed interface` / `enum` + `when`으로 표현하고 `else`를 두지 않는다.
- JPA 엔티티는 `data class`로 만들지 않는다. `equals`/`hashCode`는 식별자 기준으로 직접 정의한다.
- 확장 함수는 그것을 쓰는 모듈 안에 둔다. 전역 유틸 모듈을 만들지 않는다.

## 코어 모듈 구조 (Modulith)

- 모듈은 `io.github.ticketing.<모듈명>` 직계 패키지다.
- 다른 모듈에 공개하는 것은 **이벤트 클래스와 최소한의 API 타입**뿐이며 모듈 루트 패키지에 둔다. 나머지는 하위 패키지에 두어 비공개로 한다.
- 모듈 간 호출은 이벤트로 한다. 발행은 `ApplicationEventPublisher`, 수신은 `@ApplicationModuleListener`.
- 이벤트는 불변이고 과거형 이름을 쓴다 (`BookingConfirmed`, `PaymentFailed`). 엔티티가 아니라 ID와 필요한 값만 담는다. 추후 Kafka로 외부화되므로 직렬화 가능한 단순 타입만 쓴다.
- 이벤트 리스너는 **멱등**하게 만든다. 같은 이벤트가 다시 와도 결과가 같아야 한다.
- 모듈을 추가하거나 의존을 바꾸면 Modulith 검증 테스트를 실행한다.

## 헥사고날 (`booking`, `payment`, `settlement`)

```
<module>
├── domain            순수 Kotlin. Spring, JPA, jOOQ import 금지
├── application
│   ├── port.provided 유스케이스 인터페이스 + Command/Result 타입
│   ├── port.required XxxCommandPort(쓰기), XxxQueryPort(조회), 외부 시스템 포트
│   └── service       유스케이스 구현. 트랜잭션 경계
└── adapter
    ├── provided.web / provided.event
    └── required.jpa / required.jooq / required.redis / required.pg
```

- 의존 방향: `adapter → application → domain`. 반대 방향 import 금지. ArchUnit으로 검증한다.
- 도메인 모델과 JPA 엔티티를 분리한다. 변환은 `adapter.required.jpa` 안의 매퍼가 맡는다.
- 컨트롤러는 `port.provided`만 호출한다. 요청/응답 DTO는 `adapter.provided.web`에 두고 도메인 타입을 그대로 응답으로 내보내지 않는다.
- 비즈니스 규칙(상태 전이, 최대 4매 등)은 `domain`에 둔다. 서비스는 흐름 조율만 한다.

계층형 모듈(`member`, `catalog`, `notification`)은 `web / service / repository` 정도로 단순하게 유지한다. 포트를 억지로 만들지 않는다.

## 영속성

- **쓰기는 JPA, 조회는 jOOQ.** 조회용 쿼리를 JPA(JPQL, 연관 탐색)로 작성하지 않는다.
- jOOQ 조회 결과는 조회 전용 DTO로 직접 매핑한다. 엔티티로 받지 않는다.
- 명령 처리 중의 읽기(애그리거트 로드, 존재 확인)는 화면·목록용 조회가 아니므로 JPA(Spring Data 메서드, JPQL)로 해도 된다.
- **JPA는 Spring Data `JpaRepository`로만 쓴다.** 어댑터에서 `EntityManager`를 직접 주입해 `persist`·`merge`·`createQuery` 등을 호출하지 않는다. JPQL이 필요하면 리포지토리 메서드의 `@Query`로 둔다. 식별자를 직접 할당하는 엔티티는 `save`가 `merge`(사전 SELECT)로 가지 않도록 `Persistable`로 신규 여부를 알린다.
- **문자열 SQL을 쓰지 않는다.** `createNativeQuery`, `@Query(nativeQuery = true)`, `JdbcTemplate` 문자열 쿼리 금지. 이유: 컬럼 이름 오타나 스키마 변경이 컴파일 단계에서 드러나지 않는다.
- JPA로 표현할 수 없는 쓰기(`ON CONFLICT` upsert, 엔티티가 없는 테이블)와 엔티티가 없는 테이블의 읽기는 jOOQ DSL로 한다. 이때 명령 어댑터(`required.jpa`)가 `DSLContext`를 함께 쓸 수 있다.
- 같은 트랜잭션에서 JPA와 jOOQ를 섞으면 jOOQ 쿼리는 JPA의 flush를 일으키지 않는다. jOOQ가 아직 flush되지 않은 JPA 변경에 의존하면 먼저 `flush()`하고, 그렇지 않으면 jOOQ 쓰기를 JPA `persist`보다 앞에 둔다.
- JPA 연관관계는 같은 애그리거트 안에서만 쓴다. 다른 애그리거트·다른 모듈은 ID로 참조한다. 모든 연관은 `LAZY`.
- `open-in-view`는 끈다.
- 스키마 변경은 Flyway 마이그레이션으로만 한다. `ddl-auto`는 `validate`. 적용된 마이그레이션 파일은 수정하지 않고 새 파일을 추가한다.
- 테이블은 해당 모듈의 스키마에 만든다. 스키마를 넘는 외래 키·조인 금지.
- 마이그레이션을 바꾸면 jOOQ 코드를 다시 생성한다. 생성 코드는 수정하지 않고 커밋하지 않는다.
- 금액은 `BigDecimal`(DB `numeric`), 시각은 `Instant`(DB `timestamptz`). 현재 시각은 주입된 `Clock`에서 얻는다.

## 트랜잭션과 동시성

- `@Transactional`은 `application.service`에만 둔다. 조회는 `readOnly = true`.
- 트랜잭션 안에서 외부 호출(PG, HTTP)을 하지 않는다. 외부 호출은 이벤트 리스너나 트랜잭션 밖에서 한다.
- Redis 좌석 선점은 Lua 스크립트로 원자적으로 처리한다. 여러 명령을 나눠 보내는 방식으로 구현하지 않는다.
- 선점 해제는 **선점한 주인만** 할 수 있어야 한다 (값에 소유자 식별자를 넣고 비교 후 삭제).
- Redis와 DB는 하나의 트랜잭션이 아니다. DB 커밋 실패 시 Redis 선점을 되돌리는 경로, Redis TTL이 누락됐을 때 Batch가 보정하는 경로를 항상 함께 고려한다.
- 좌석 확정 시 유니크 제약 위반은 "이미 판매됨"으로 변환해 처리한다. 500으로 흘려보내지 않는다.

## Virtual Thread (`core`, `fake-pg`)

- `spring.threads.virtual.enabled=true`.
- 블로킹 I/O를 감싸는 `synchronized` 대신 `ReentrantLock`을 쓴다. 라이브러리 pinning 여부는 부하 테스트 단계에서 확인한다.
- `ThreadLocal`에 큰 객체를 캐싱하지 않는다.
- 스레드 풀로 동시성을 제한하지 않는다. 제한이 필요하면 `Semaphore`나 커넥션 풀 크기로 한다.

## WebFlux + Coroutines (`realtime`, `gateway`)

- 컨트롤러와 서비스는 `suspend` 함수와 `Flow`로 작성한다. `Mono`/`Flux`는 프레임워크 경계에서만 쓴다.
- **블로킹 호출 금지.** `runBlocking`, `Thread.sleep`, 블로킹 Redis/HTTP 클라이언트를 쓰지 않는다. Redis는 reactive 클라이언트를 쓴다.
- `GlobalScope`를 쓰지 않는다. 구조화된 동시성을 지키고, `CancellationException`을 삼키지 않는다.
- SSE는 `Flow<ServerSentEvent<T>>`로 내보낸다. 이벤트에 `id`를 붙여 `Last-Event-ID` 재연결을 지원하고, 주기적 heartbeat를 보낸다.
- 클라이언트 연결이 끊기면 Redis 구독과 대기열 자원을 정리한다.
- `realtime`은 인스턴스가 여러 개 떠도 동작해야 한다. 인스턴스 메모리에만 있는 상태에 의존하지 않는다.

## Gateway

- 필터는 검증과 라우팅만 한다. 비즈니스 로직을 넣지 않는다.
- 입장 토큰 검증 실패는 대기열로 돌려보낼 수 있는 구분된 오류 코드로 응답한다.

## API

- 경로는 `/api/v1/...`, 복수형 명사.
- 오류 응답은 RFC 9457 `ProblemDetail`. 도메인 오류에는 안정적인 `code` 필드를 넣는다 (`SEAT_ALREADY_HELD`, `BOOKING_EXPIRED` 등).
- 예외 → 응답 변환은 `@RestControllerAdvice` 한 곳에서 한다.
- 입력 검증은 컨트롤러 경계에서 Bean Validation으로, 도메인 불변식은 도메인에서 한다.
- 목록 조회는 커서 기반 페이지네이션을 기본으로 한다.
- 결제·예매 생성처럼 재시도될 수 있는 쓰기 API는 `Idempotency-Key` 헤더를 받는다.

## 보안

- 인가는 URL 패턴과 메서드 보안으로 서버에서 강제한다. BFF나 Gateway를 통과했다고 신뢰하지 않는다.
- 리소스 소유자 확인(내 예매인가)은 `application.service`에서 한다.
- 비밀번호, 토큰, 결제 정보를 로그에 남기지 않는다.
- PG 웹훅은 서명을 검증한다.

## Batch (`core-batch`)

- Job은 **재실행해도 결과가 같아야 한다.** JobParameter에 대상 날짜를 넣고, 쓰기는 upsert 또는 삭제 후 재적재로 한다.
- reader는 jOOQ, chunk 지향 처리. 전체를 메모리에 올리지 않는다.
- `settlement`는 다른 모듈 스키마를 읽지 않는다. 이벤트로 받아 자기 스키마에 쌓은 사본만 읽는다.

## 로깅

- SLF4J + 파라미터 방식(`log.info("... {}", id)`). 문자열 연결로 로그를 만들지 않는다.
- 예외를 잡았으면 처리하거나 다시 던진다. 로그만 남기고 삼키지 않는다.
- 상태 전이와 결제 결과에는 `bookingId`, `paymentId`를 포함한다.

## 테스트

- 도메인: 프레임워크 없는 순수 단위 테스트. 상태 전이의 허용·금지 경로를 모두 다룬다.
- 유스케이스: 출력 포트를 가짜 구현(fake)으로 대체한다. mock 남용을 피한다.
- 어댑터와 통합: Testcontainers(PostgreSQL, Redis). H2나 embedded Redis를 쓰지 않는다.
- 모듈: `@ApplicationModuleTest`, 이벤트 발행은 `Scenario` / `PublishedEvents`로 검증한다.
- 동시성: 같은 좌석에 대한 동시 요청을 실제로 병렬 실행하고 "성공 1건, DB 확정 1건"을 단언한다.
- 시간 의존 로직은 `Clock`을 고정해 테스트한다. `Thread.sleep`으로 기다리지 않는다.
- 테스트 이름은 한국어 백틱 문장으로 쓴다 (``fun `만료된 예매는 확정할 수 없다`()``).
- 코루틴 테스트는 `runTest`를 쓴다.
