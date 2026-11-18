# core 작성 지침

WebMVC와 [Virtual Thread 공통 지침](backend.md#virtual-thread)을 적용한다.

## 코어 모듈 구조 (Modulith)

- 모듈은 `io.github.ticketing.<모듈명>` 직계 패키지다.
- 다른 모듈에 공개하는 것은 **이벤트 클래스와 최소한의 API 타입**뿐이며 모듈 루트 패키지에 둔다. 나머지는 하위 패키지에 두어 비공개로 한다. 공유 계약 이벤트의 소스 위치와 호환 규칙은 [contract 작성 지침](backend-contract.md)을 따른다.
- 모듈 간 호출은 이벤트로 한다. 발행은 `ApplicationEventPublisher`를 쓴다. 로컬 이벤트는 `@ApplicationModuleListener`, Kafka로 외부화한 결제 계약 5종은 `@KafkaListener`로 수신한다([이벤트 카탈로그](../architecture/events.md#kafka-외부화-대상-m6)).
- 이벤트는 불변이고 과거형 이름을 쓴다 (`BookingConfirmed`, `PaymentFailed`). 엔티티가 아니라 ID와 필요한 값만 담는다. Kafka 외부화 대상도 직렬화 가능한 단순 타입만 쓴다.
- 이벤트 리스너는 **멱등**하게 만든다. 같은 이벤트가 다시 와도 결과가 같아야 한다.
- 모듈을 추가하거나 의존을 바꾸면 Modulith 검증 테스트를 실행하고, `./gradlew :core:modulithDocs`로 `docs/modulith/` 다이어그램을 다시 생성해 같은 변경에 포함한다. 기본 `test`는 문서를 생성하지 않는다.

## 헥사고날 (`booking`, `payment`, `settlement`, `coupon`)

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

- 의존 방향: `adapter → application → domain`. 반대 방향 import 금지. 현재 ArchUnit 계층 검사의 대상은 `booking`, `payment`, `settlement`다. `coupon`에도 설계 규칙을 적용하며 검사 범위 확대는 별도 작업으로 확인한다.
- 도메인 모델과 JPA 엔티티를 분리한다. 변환은 `adapter.required.jpa` 안의 매퍼가 맡는다.
- 컨트롤러는 `port.provided`만 호출한다. 요청/응답 DTO는 `adapter.provided.web`에 두고 도메인 타입을 그대로 응답으로 내보내지 않는다.
- 비즈니스 규칙(상태 전이, 최대 4매 등)은 `domain`에 둔다. 서비스는 흐름 조율만 한다.

계층형 모듈(`member`, `catalog`, `audit`, `notification`)은 역할에 맞게 `web / service / repository` 등으로 단순하게 유지한다. 포트를 억지로 만들지 않는다.

## JPA 엔티티

- JPA 엔티티는 `data class`로 만들지 않는다. `equals`는 식별자 기준으로 정의한다. 프록시와 비교될 수 있으므로 `other is 엔티티클래스`로 비교하고 `id`는 getter로 읽는다. `hashCode`는 영속 전후에 유지되고 `equals`가 참인 프록시와 실제 엔티티에서 같아야 한다. 구현을 변경할 때 두 조건을 확인한다. 식별자로 계산하면 영속 전후에 해시가 바뀔 수 있으며, 클래스 기준 계산도 프록시와 같은 값을 내는지 확인해야 한다.
- JPA 엔티티(`@Embeddable` 포함)의 영속 속성에는 `val`을 쓰지 않는다. `@Id`, `@EmbeddedId`, 컬렉션, `updatable = false` 컬럼을 포함해 생성자와 본문 모두 `var`로 선언한다. `val`은 영속성 provider가 reflection으로 바꿀 수 있어 실제로 불변이 아니고, final 필드와 setter가 없어 JPA Auditing 같은 기능이 어긋나며, Kotlin 데이터 흐름 분석이 틀려 런타임 오류가 날 수 있다 (IDE의 JPA 검사도 같은 이유로 경고한다). 변경하면 안 되는 컬럼은 `updatable = false`로 표현한다. 엔티티 외 도메인 모델의 `val` 원칙은 그대로다.
- **setter 가시성.** `domain` 패키지에 둔 엔티티(계층형 모듈 `member`, `catalog`, `notification`)의 영속 속성은 `var` + `protected set`으로 선언해 외부 변경을 막는다. `private set`은 `allOpen`이 속성을 open으로 만들어 컴파일 오류가 나므로 쓰지 않는다. 헥사고날 모듈의 `*JpaEntity`는 같은 어댑터의 매퍼가 값을 채우므로 예외다. 대신 어댑터 밖으로 노출하지 않는다 (`internal` 또는 패키지 비공개 사용).
- **컬렉션 노출.** Kotlin `List`는 읽기 전용 뷰일 뿐이라 `as MutableList` 캐스팅으로 수정된다. 엔티티는 내부 컬렉션을 그대로 반환하지 않고 `Collections.unmodifiableList(...)` 또는 복사본(`toList()`)을 반환한다. 컬렉션 속성은 JPA 필드명을 유지한다.
- JPA 엔티티는 애노테이션을 변수 선언과 같은 줄에 두지 않는다. 애노테이션을 한 줄씩 쓰고, 다음 줄에 `val`/`var` 선언을 둔다.

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

## 트랜잭션

- 유스케이스의 트랜잭션 경계는 헥사고날 모듈의 `application.service`, 계층형 모듈의 `service`에 둔다. 조회에는 `readOnly = true`를 쓴다.
- 트랜잭션 안에서 외부 호출(PG, HTTP)을 하지 않는다. 외부 호출은 이벤트 리스너나 트랜잭션 밖에서 한다.

## API

- 경로는 `/api/v1/...`, 복수형 명사.
- 오류 응답은 RFC 9457 `ProblemDetail`. 도메인 오류에는 안정적인 `code` 필드를 넣는다 (`SEAT_ALREADY_HELD`, `BOOKING_EXPIRED` 등).
- 예외 → 응답 변환은 `@RestControllerAdvice` 한 곳에서 한다.
- 입력 검증은 컨트롤러 경계에서 Bean Validation으로, 도메인 불변식은 도메인에서 한다.
- 목록 조회는 커서 기반 페이지네이션을 기본으로 한다.
- 결제·예매 생성처럼 재시도될 수 있는 쓰기 API는 `Idempotency-Key` 헤더를 받는다.
- **검증 애노테이션 사용처.** 요청 DTO의 Bean Validation 애노테이션은 `@field:`를 명시한다 (`@field:NotBlank val name: String`). 컴파일러 옵션(`-Xannotation-default-target`)에 기대지 않는다. 필수 값을 non-null로 선언하면 누락이 검증 오류가 아니라 역직렬화 오류(`HttpMessageNotReadableException`)가 되므로, 응답 형식을 통일해야 하면 `ApiExceptionHandler`가 둘 다 같은 `ProblemDetail`로 변환하는지 확인한다.
- **불변식 실패의 종류.** 생성 시 인자 불변식은 `require`, 객체 상태가 허용하지 않는 호출은 `check`로 막는다. 호출자(사용자)에게 의미 있는 업무 오류는 `DomainException`(안정적 `code`)으로 던진다. `IllegalStateException`·`IllegalArgumentException`이 API 경계까지 올라가 500이 되지 않게 한다.

공개 API 경로·헤더·응답의 상세 계약은 [API 규약](../architecture/api-conventions.md)을 따른다.

## 보안

- 인가는 URL 패턴과 메서드 보안으로 서버에서 강제한다. BFF나 Gateway를 통과했다고 신뢰하지 않는다.
- 리소스 소유자 확인(내 예매인가)은 헥사고날 모듈의 `application.service`, 계층형 모듈의 `service`에서 한다.

## Batch Job

- Job은 데이터를 소유한 `core` 모듈의 `adapter.provided.batch`에 두고 `batch` 프로필에서 활성화한다. 재시작·재실행해도 결과의 정합성을 유지한다. 날짜 기준 Job은 `targetDate`와 `run.id`, 예매·쿠폰 보정 등 시각 기준 Job은 `run.id`를 쓴다. 각 Job의 파라미터와 재실행 조건은 [Batch 설계](../architecture/batch.md)를 따른다.
- 대량 읽기에는 jOOQ와 chunk 처리를 사용해 메모리 사용량을 제한한다. Step의 처리 방식과 쓰기 전략은 Job별로 정한다.

실행 설정은 [core-batch 작성 지침](backend-core-batch.md)을 따른다.

## 도메인별 규칙

### booking

- Redis 좌석 선점은 Lua 스크립트로 원자적으로 처리한다. 여러 명령을 나눠 보내는 방식으로 구현하지 않는다.
- 선점 해제는 **선점한 주인만** 할 수 있어야 한다 (값에 소유자 식별자를 넣고 비교 후 삭제).
- Redis와 DB는 하나의 트랜잭션이 아니다. DB 커밋 실패 시 Redis 선점을 되돌리는 경로, Redis TTL이 누락됐을 때 Batch가 보정하는 경로를 항상 함께 고려한다.
- 좌석 확정 시 유니크 제약 위반은 "이미 판매됨"으로 변환해 처리한다. 500으로 흘려보내지 않는다.

좌석 상태 발행과 스냅샷 반영의 상세 규칙은 [좌석 변경 스트림](../architecture/events.md#3-좌석-변경-스트림-redis-streams)을 따른다.

### payment

- 결제 요청과 웹훅은 멱등 키로 중복을 처리한다. PG 웹훅의 서명을 검증한다.
- 예매는 `orderId`로 참조한다. 결제 계약 이벤트의 패키지와 의존 방향은 [코어 모듈 규칙](../architecture.md#모듈-규칙)을 따른다.

### settlement

- 다른 모듈 스키마를 읽지 않는다. 이벤트로 받아 자기 스키마에 저장한 사본만 읽는다. 정산 기준은 [Batch 설계](../architecture/batch.md)를 따른다.

### coupon

- 예약·해제·사용 확정의 소유권과 보상 흐름은 [쿠폰 예약 Saga](../adr/0035-coupon-module-reservation-saga.md)를 따른다.
- 한정 수량의 Redis 발급과 DB 정합성 보정은 [선착순 발급 설계](../adr/0037-limited-coupon-claim-redis-lua.md)와 [Batch 설계](../architecture/batch.md)를 따른다.

### 예매·결제 로깅

- 상태 전이와 결제 결과에는 `bookingId`, `paymentId`를 포함한다.
