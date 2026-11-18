# Backend 지침

Kotlin, Spring Boot 4.x, Java 25. 설계 근거는 `docs/architecture.md`.

## 빌드

- Gradle Kotlin DSL. 의존성 버전은 `gradle/libs.versions.toml`(version catalog)에만 적는다.
- 공통 빌드 설정은 convention plugin으로 모으고, 각 모듈의 `build.gradle.kts`에는 그 모듈 고유의 것만 둔다.
- `realtime`과 `gateway`에는 JPA, JDBC, jOOQ 의존성을 추가하지 않는다.
- 포맷은 ktlint, 정적 분석은 detekt(`build-logic`의 `ticketing.kotlin-quality`). 규칙 위반은 코드를 고쳐 해결하고, `@Suppress`나 `detekt-baseline.xml` 항목 추가는 사용자와 합의한 경우에만 한다.

## Kotlin

- `!!`를 쓰지 않는다. null은 타입과 `?:`, `requireNotNull`, `checkNotNull`로 다룬다.
- 도메인 모델은 `val` 우선. 상태 변경은 의미 있는 이름의 메서드로만 한다 (`booking.confirm()`), setter를 노출하지 않는다.
- 식별자는 `@JvmInline value class`로 감싼다 (`BookingId`, `SeatId`, `MemberId`). 서로 다른 ID를 `Long`으로 섞어 쓰지 않는다.
- 상태와 결과의 분기는 `sealed interface` / `enum` + `when`으로 표현하고 `else`를 두지 않는다.
- JPA 엔티티는 `data class`로 만들지 않는다. `equals`는 식별자 기준으로, `hashCode`는 클래스 기준 상수(`javaClass.hashCode()`)로 직접 정의한다. 식별자로 `hashCode`를 만들면 영속 전후에 값이 바뀌어 `Set`·`Map`에서 엔티티를 잃는다. 프록시와 비교될 수 있으므로 `equals`는 `other is 엔티티클래스`로 비교하고 `id`는 getter로 읽는다.
- JPA 엔티티(`@Embeddable` 포함)의 영속 속성에는 `val`을 쓰지 않는다. `@Id`, `@EmbeddedId`, 컬렉션, `updatable = false` 컬럼을 포함해 생성자와 본문 모두 `var`로 선언한다. `val`은 영속성 provider가 reflection으로 바꿀 수 있어 실제로 불변이 아니고, final 필드와 setter가 없어 JPA Auditing 같은 기능이 어긋나며, Kotlin 데이터 흐름 분석이 틀려 런타임 오류가 날 수 있다 (IDE의 JPA 검사도 같은 이유로 경고한다). 변경하면 안 되는 컬럼은 `updatable = false`로 표현한다. 엔티티 외 도메인 모델의 `val` 원칙은 그대로다.
- 확장 함수는 그것을 쓰는 모듈 안에 둔다. 전역 유틸 모듈을 만들지 않는다.

### 엔티티·DTO의 Kotlin 함정

- **setter 가시성.** `domain` 패키지에 둔 엔티티(계층형 모듈 `member`, `catalog`, `notification`)의 영속 속성은 `var` + `protected set`으로 선언해 외부 변경을 막는다. `private set`은 `allOpen`이 속성을 open으로 만들어 컴파일 오류가 나므로 쓰지 않는다. 헥사고날 모듈의 `*JpaEntity`는 같은 어댑터의 매퍼가 값을 채우므로 예외다. 대신 어댑터 밖으로 노출하지 않는다 (`internal` 또는 패키지 비공개 사용).
- **컬렉션 노출.** Kotlin `List`는 읽기 전용 뷰일 뿐이라 `as MutableList` 캐스팅으로 수정된다. 엔티티는 내부 컬렉션을 그대로 반환하지 않고 `Collections.unmodifiableList(...)` 또는 복사본(`toList()`)을 반환한다. 컬렉션 속성은 JPA 필드명을 유지한다.
- **검증 애노테이션 사용처.** 요청 DTO의 Bean Validation 애노테이션은 `@field:`를 명시한다 (`@field:NotBlank val name: String`). 컴파일러 옵션(`-Xannotation-default-target`)에 기대지 않는다. 필수 값을 non-null로 선언하면 누락이 검증 오류가 아니라 역직렬화 오류(`HttpMessageNotReadableException`)가 되므로, 응답 형식을 통일해야 하면 `ApiExceptionHandler`가 둘 다 같은 `ProblemDetail`로 변환하는지 확인한다.
- **불변식 실패의 종류.** 생성 시 인자 불변식은 `require`, 객체 상태가 허용하지 않는 호출은 `check`로 막는다. 호출자(사용자)에게 의미 있는 업무 오류는 `DomainException`(안정적 `code`)으로 던진다. `IllegalStateException`·`IllegalArgumentException`이 API 경계까지 올라가 500이 되지 않게 한다.

### 가독성 (코드 스타일)

- 변수명에 약어를 쓰지 않는다. `b`, `p`, `a`, `c`, `x` 같은 이름 대신 상태나 역할이 드러나는 이름을 쓴다 (`pendingBooking`, `payment`, `firstLoaded`).
- 테스트는 given(준비), when(실행), then(검증) 사이에 빈 줄을 둔다. `// given` 같은 주석은 달지 않는다.
- `main` 소스는 논리 단위(선언 그룹, 검증, 저장, 발행, 로깅)마다 빈 줄로 구분한다. 한 줄에 `;`로 여러 문장을 잇지 않는다.
- JPA 엔티티는 애노테이션을 변수 선언과 같은 줄에 두지 않는다. 애노테이션을 한 줄씩 쓰고, 다음 줄에 `val`/`var` 선언을 둔다.
- 줄 단위 구분은 의미를 바꾸지 않는다. 검증 순서가 예외 메시지에 영향을 주면 기존 순서를 유지한다.

## 코어 모듈 구조 (Modulith)

- 모듈은 `io.github.ticketing.<모듈명>` 직계 패키지다.
- 다른 모듈에 공개하는 것은 **이벤트 클래스와 최소한의 API 타입**뿐이며 모듈 루트 패키지에 둔다. 나머지는 하위 패키지에 두어 비공개로 한다.
- 모듈 간 호출은 이벤트로 한다. 발행은 `ApplicationEventPublisher`를 쓴다. 로컬 이벤트는 `@ApplicationModuleListener`, Kafka로 외부화한 결제 계약 5종은 `@KafkaListener`로 수신한다([이벤트 카탈로그](../architecture/events.md#kafka-외부화-대상-m6)).
- 이벤트는 불변이고 과거형 이름을 쓴다 (`BookingConfirmed`, `PaymentFailed`). 엔티티가 아니라 ID와 필요한 값만 담는다. Kafka 외부화 대상도 직렬화 가능한 단순 타입만 쓴다.
- 이벤트 리스너는 **멱등**하게 만든다. 같은 이벤트가 다시 와도 결과가 같아야 한다.
- 모듈을 추가하거나 의존을 바꾸면 Modulith 검증 테스트를 실행하고, `./gradlew :core:modulithDocs`로 `docs/modulith/` 다이어그램을 다시 생성해 함께 커밋한다. 기본 `test`는 문서를 생성하지 않는다.

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
- readiness 전 내부 예열을 수행하는 `ApplicationRunner`의 기동 스레드만 제한 시간 동안 Future 완료를 기다릴 수 있다([ADR 0029](../adr/0029-layered-startup-warmup.md)). 예열 HTTP·재시도·지연은 reactive 연산이며 요청 이벤트 루프에는 블로킹을 넣지 않는다.
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

### 테스트 작성 보조 규칙

- 단언은 AssertJ로 통일한다 (`assertThat`, `assertThatThrownBy`). JUnit `Assertions`와 섞지 않는다.
- 같은 객체 생성 코드를 두 테스트 클래스 이상이 쓰면 대상 패키지의 `object XxxFixture`로 모은다. 선택 값은 기본 인자로 받는다 (`createBooking(status = ...)`). 한 클래스에서만 쓰면 `private fun` 헬퍼로 둔다.
- 특정 상태의 객체는 도메인 메서드를 호출해 만든다 (`createPending().apply { confirm() }`). 리플렉션이나 랜덤 객체 생성으로 도메인 상태를 주입하면 불변식을 우회하므로 쓰지 않는다. 리플렉션은 영속 후에만 생기는 `id`가 필요할 때로 한정한다.
- 테스트 클래스의 공용 상태가 Spring 빈이 아니면 `private lateinit var` + `@BeforeEach`를 쓸 수 있다. JUnit은 테스트마다 인스턴스를 새로 만든다.
- 엔티티의 컬렉션 불변성, `@field:` 검증 동작처럼 Kotlin 특성에서 오는 규칙은 회귀 방지 테스트를 한 번씩 둔다.

### 테스트 의존성 주입

- Spring 빈은 생성자의 `private val`로 주입한다. 하위 테스트가 사용하는 공통 베이스의 의존성은 필요한 가시성으로 선언하고, 구체 테스트의 생성자에서 전달한다. `@Autowired lateinit var` 필드 주입은 쓰지 않는다.
- Spring 생성자 주입을 사용하는 모듈의 테스트 리소스에 `junit-platform.properties`를 두고 `spring.test.constructor.autowire.mode=all`을 설정한다. 생성자마다 `@Autowired`를 반복하지 않는다.
- `ApplicationEvents`는 테스트 메서드 범위의 의존성이므로 필요한 테스트 메서드의 매개변수로 주입한다.
- `@MockitoBean` 필드는 Spring 빈 대체를 선언하므로 `lateinit var`를 유지할 수 있다. 일반 빈의 필드 주입과 구분한다.
- HTTP 테스트는 `MockMvc` 대신 `MockMvcTester`를 주입받아 `mockMvcTester.get()` 등으로 요청을 만들고, `assertThat(result).hasStatus(...)`·`bodyJson()`으로 검증한다.

### Spring 컨텍스트 재사용

- 코어 통합 테스트는 `IntegrationTestSupport`를 상속하고 공통 애플리케이션 클래스·MockMvc·컨테이너 설정을 사용한다. 하위 테스트에 같은 `@SpringBootTest`·`@AutoConfigureMockMvc` 설정을 반복하지 않는다.
- 공통 테스트 설정은 명시적으로 재사용한다. 클래스별 `@Import`, 프로필, 테스트 프로퍼티, 빈 대체는 다른 컨텍스트를 만들 수 있으므로 테스트 동작에 필요한 경우에만 추가한다. Clock·가짜 PG·배치 실패 주입 같은 대체는 `CommonTestConfiguration`의 공용 가변 빈으로 두고 `IntegrationTestSupport`가 매 테스트 전에 초기화한다. 테스트가 필요한 시각·응답은 `@BeforeEach`에서 설정한다. `@MockitoBean`·클래스별 빈 대체는 공용 빈으로 표현할 수 없을 때만 쓴다. 시작 시 데이터를 넣는 seed 프로필은 격리를 유지한다.
- `@DirtiesContext`는 컨텍스트 자체를 복구할 수 없게 변경한 경우에만 쓴다. 테스트 데이터 정리를 대신하는 용도로 사용하지 않는다.
- 같은 컨텍스트의 테스트는 고유 식별자로 데이터를 구분하고(잡처럼 전역을 훑는 결과는 자기 데이터 기준으로 단언한다), 비동기 이벤트 처리가 끝난 뒤 검증한다. 공유 Clock·가짜 구현의 가변 상태는 각 테스트가 필요한 초기 상태로 설정한다.

### Testcontainers 재사용

- 코어 PostgreSQL·Redis 컨테이너는 `TestcontainersConfiguration`의 JVM 단위 싱글턴을 공유한다. 테스트 클래스나 컨텍스트마다 컨테이너를 새로 만들지 않는다.
- 공유 컨테이너의 수명은 Spring 빈·JUnit 테스트 클래스의 수명과 분리한다. `@Container`·`@Testcontainers`로 클래스 종료 시 정리하거나, Spring 컨테이너 빈의 종료 처리·`stop()`·`close()`로 공유 컨테이너를 중단하지 않는다. JVM 종료 후 정리는 Testcontainers의 Ryuk에 맡긴다.
- 접속 정보는 공통 `DynamicPropertyRegistrar`에서 등록한다. 서로 다른 컨텍스트는 PostgreSQL 데이터베이스와 Redis DB 번호를 따로 배정해 미완료 이벤트 재발행·캐시·좌석 선점 상태를 격리한다.
- 재사용 범위는 같은 테스트 JVM이다. Gradle 실행 간 재사용을 위한 `withReuse(true)`나 개인 환경 설정을 기본 전제로 추가하지 않는다.
- 재사용 구성을 바꾸면 테스트 필터 없이 관련 테스트를 실행하고, 컨텍스트·컨테이너 기동 수와 스위트 시간을 비교한다. 측정 방법과 기존 결과는 [통합 테스트 재사용 개선](../test-performance.md)을 참고한다.
