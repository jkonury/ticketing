# Step 4: settlement-ingest

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/booking/BookingConfirmed.kt`, `BookingCancelled.kt` (step 3에서 `performanceId`, `organizerId` 추가됨)
- `core/src/main/java/io/github/ticketing/settlement/package-info.java` (모듈 선언만 있음)
- 참고 구현(리스너·jOOQ 쓰기 형태): `core/src/main/kotlin/io/github/ticketing/notification/`의 리스너, `booking/adapter/required/jpa/BookingPersistenceAdapter.kt`의 `insertSoldSeats`(jOOQ `ON CONFLICT DO NOTHING`)
- 모듈 테스트 형태: `core/src/test/kotlin/io/github/ticketing/notification/NotificationModuleTests.kt`
- `core/src/test/kotlin/io/github/ticketing/ModularityTests.kt`, `DocumentationTests.kt`, `HexagonalArchitectureTests.kt`
- `docs/rules/backend.md § 헥사고날`, `§ 영속성`, `§ 테스트`

## 작업

settlement 모듈(헥사고날)을 처음 만든다. 예매 확정·환불 취소 이벤트를 받아 **settlement 스키마에 사본**으로 쌓는다. 정산 계산과 Job은 step 5다.

### 1. 마이그레이션 `V8__create_settlement_copy_tables.sql` (settlement 스키마)

```
settlement.booking_sale      -- BookingConfirmed 사본
  booking_id     bigint PK
  performance_id bigint NOT NULL
  organizer_id   bigint NOT NULL
  schedule_id    bigint NOT NULL
  amount         numeric(12,0) NOT NULL
  confirmed_at   timestamptz NOT NULL      -- 인덱스
  received_at    timestamptz NOT NULL

settlement.booking_refund    -- refundRequired=true인 BookingCancelled 사본
  booking_id     bigint PK
  performance_id bigint NOT NULL
  organizer_id   bigint NOT NULL
  reason         varchar NOT NULL
  cancelled_at   timestamptz NOT NULL      -- 인덱스
  received_at    timestamptz NOT NULL
```

- 다른 스키마로 외래 키를 걸지 않는다. 금액 타입은 기존 `booking.booking.total_amount` 정의와 맞춘다(`rg -n total_amount core/src/main/resources/db/migration`로 확인).
- 마이그레이션 후 jOOQ 코드는 빌드가 다시 생성한다. 생성 코드는 커밋하지 않는다.

### 2. 패키지와 시그니처 (`io.github.ticketing.settlement`)

```
application.port.provided.RecordBookingEventUseCase
    fun recordSale(command: RecordSaleCommand)
    fun recordRefund(command: RecordRefundCommand)
application.port.required.SettlementCopyCommandPort
    fun insertSaleIfAbsent(...): Boolean
    fun insertRefundIfAbsent(...): Boolean
application.service.RecordBookingEventService      // @Transactional, received_at은 주입된 Clock
adapter.provided.event.BookingEventListener         // @ApplicationModuleListener: BookingConfirmed, BookingCancelled
adapter.required.jooq.SettlementCopyJooqAdapter     // jOOQ insert ... ON CONFLICT DO NOTHING
```

- 사본 테이블은 JPA 엔티티를 만들지 않는다. 엔티티 없는 테이블의 쓰기는 jOOQ DSL로 한다.
- `BookingCancelled` 중 `refundRequired=false`는 기록하지 않는다(결제 전 취소·결제 실패는 매출이 없다).
- 리스너는 멱등하다: 같은 이벤트가 다시 와도 행이 하나다(`ON CONFLICT (booking_id) DO NOTHING`). 중복이면 debug 로그만 남긴다.
- `SEAT_ALREADY_SOLD`, `AMOUNT_MISMATCH`처럼 확정 없이 환불된 취소도 `booking_refund`에 기록한다. 정산에서 차감할지는 확정 사본 존재 여부로 step 5가 판단한다. 이유: 이벤트 도착 순서와 무관하게 사본은 사실 그대로 쌓는다.
- 도메인 규칙이 거의 없으므로 `domain`에 억지로 모델을 만들지 않아도 된다. 만들면 순수 Kotlin이어야 한다.

### 3. 모듈 의존

- settlement → booking(이벤트 타입) 의존이 생긴다. `ModularityTests`를 실행하고, `DocumentationTests`를 실행해 바뀐 `docs/modulith/*.puml`을 함께 남긴다.

### 4. 테스트

- `core/src/test/kotlin/io/github/ticketing/settlement/SettlementModuleTests.kt`: `@ApplicationModuleTest` + `Scenario`로 이벤트를 발행하고 사본 행을 확인한다.
  - 확정 이벤트 → `booking_sale` 1행, 값 일치
  - 같은 확정 이벤트 두 번 → 1행
  - `refundRequired=true` 취소 → `booking_refund` 1행, `false` → 0행
- 고유한 `bookingId`를 써서 다른 테스트와 데이터를 구분한다.

## Acceptance Criteria

```bash
test -f core/src/main/resources/db/migration/V8__create_settlement_copy_tables.sql
test -d core/src/main/kotlin/io/github/ticketing/settlement/adapter/provided/event
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.settlement*" --tests "*ModularityTests" --tests "*HexagonalArchitectureTests" --tests "*DocumentationTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. `run-tests.sh :core:test --tests "*SettlementModuleTests"`를 먼저 통과시킨 뒤 AC 전체를 실행한다.
2. `git status --short docs/modulith`로 puml 변경이 생겼는지 확인해 완료 보고에 적는다.

## 금지사항

- settlement에서 `booking`, `payment` 스키마를 읽거나 조인하지 마라. 이유: 모듈별 스키마 경계. 필요한 값은 이벤트로 받는다.
- booking의 내부 패키지(`booking.domain`, `booking.application` 등)를 import하지 마라. 이유: 모듈 루트의 이벤트 타입만 공개 API다.
- 이 step에서 정산 계산·Job·조회 API를 만들지 마라. 이유: step 5, 6의 범위다.
- 문자열 SQL(`JdbcTemplate`, native query)을 쓰지 마라. 이유: ArchUnit 테스트가 막고, 스키마 변경이 컴파일 단계에서 드러나지 않는다.
- 기존 테스트를 깨뜨리지 마라.
