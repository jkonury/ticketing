# Step 3: booking-event-enrichment

- model: 기본 / reasoning_effort: medium

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/booking/BookingConfirmed.kt`, `BookingCancelled.kt`
- `core/src/main/kotlin/io/github/ticketing/booking/domain/ScheduleCopy.kt`
- `core/src/main/kotlin/io/github/ticketing/booking/application/port/required/ScheduleSeatCommandPort.kt`와 구현 `adapter/required/jpa/ScheduleSeatPersistenceAdapter.kt`
- 발행 위치: `booking/application/service/PaymentResultService.kt`(확정 1곳, 취소 2곳: `cancelWithRefund`, `onPaymentFailed`), `booking/application/service/CancelBookingService.kt`
- 생성자를 쓰는 테스트: `core/src/test/kotlin/io/github/ticketing/notification/service/BookingNotificationListenerTests.kt`, `notification/NotificationModuleTests.kt`, `e2e/PaymentFailurePathTests.kt`, `payment/flow/PaymentFlowTests.kt`, `booking/application/flow/BookingPaymentFlowTests.kt`
- `docs/rules/backend.md § 코어 모듈 구조 (Modulith)`, `§ 헥사고날`

## 작업

settlement(step 4)가 공연·주최자별 정산을 하도록 예매 이벤트에 공연·주최자 ID를 싣는다. booking은 이미 `booking.schedule` 사본에 두 값을 가지고 있다.

### 1. 이벤트 필드 추가 (booking 모듈 루트)

```kotlin
data class BookingConfirmed(
    val bookingId: Long, val memberId: Long, val scheduleId: Long,
    val performanceId: Long, val organizerId: Long,          // 추가
    val seatIds: List<Long>, val amount: BigDecimal, val confirmedAt: Instant,
)

data class BookingCancelled(
    val bookingId: Long, val memberId: Long, val scheduleId: Long,
    val performanceId: Long, val organizerId: Long,          // 추가
    val reason: String, val refundRequired: Boolean, val cancelledAt: Instant,
)
```

기본값을 두지 않는다. 모든 발행·생성 위치가 컴파일 오류로 드러나게 하기 위함이다. KDoc의 "settlement가 구독한다" 설명은 유지한다.

### 2. 회차 사본 조회 포트

`ScheduleSeatCommandPort`에 `fun findSchedule(scheduleId: ScheduleId): ScheduleCopy?`를 추가하고 JPA 어댑터에서 구현한다(명령 처리 중 읽기라 JPA로 해도 된다).

### 3. 발행 위치 수정

- `PaymentResultService`, `CancelBookingService`에서 이벤트를 만들 때 `findSchedule(booking.scheduleId)`로 두 값을 채운다. 같은 메서드 안에서 한 번만 조회한다.
- 회차 사본이 없으면(예매가 있는데 사본이 없는 불가능한 상태) `IllegalStateException`으로 실패시킨다. 이유: 0 같은 기본값으로 발행하면 정산에서 잘못된 공연으로 집계된다. 리스너 경로라 예외는 이벤트 발행 레지스트리가 재시도한다.
- 서비스 단위 테스트에서 쓰는 포트 fake가 있으면 `findSchedule`을 구현한다.

### 4. 테스트

- 위 테스트 파일들의 이벤트 생성자 호출을 새 시그니처로 고친다.
- 확정·취소 이벤트에 `performanceId`, `organizerId`가 회차 사본 값으로 채워지는지 검증을 추가한다. 이미 확정/취소 이벤트 발행을 검증하는 테스트(`BookingPaymentFlowTests` 등)에 단언을 더하는 방식이 좋다.

## Acceptance Criteria

```bash
rg -q 'val organizerId' core/src/main/kotlin/io/github/ticketing/booking/BookingConfirmed.kt
rg -q 'val organizerId' core/src/main/kotlin/io/github/ticketing/booking/BookingCancelled.kt
rg -q 'fun findSchedule' core/src/main/kotlin/io/github/ticketing/booking/application/port/required/ScheduleSeatCommandPort.kt
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.booking*" --tests "io.github.ticketing.notification*" --tests "io.github.ticketing.payment*" --tests "io.github.ticketing.e2e*" --tests "*ModularityTests"
./scripts/agent/test/check-summary.sh :core:ktlintCheck :core:detekt
```

## 검증 절차

1. 컴파일 오류부터 해결하고 `run-tests.sh :core:test --tests "*BookingPaymentFlowTests"`를 먼저 통과시킨다.
2. 그 뒤 AC 전체를 실행한다.

## 금지사항

- booking이 catalog를 직접 조회하지 마라. 이유: 모듈 간 접근은 이벤트로만 하며, 두 값은 이미 `booking.schedule` 사본에 있다.
- 새 필드에 기본값(`= 0L` 등)을 두지 마라. 이유: 빠뜨린 발행 위치가 컴파일 단계에서 드러나야 한다.
- `BookingExpired`는 바꾸지 마라. 이유: 정산에 쓰지 않는다(만료 예매는 확정된 적이 없다).
- 이벤트에 엔티티나 도메인 타입을 넣지 마라. 이유: 추후 Kafka로 외부화되므로 단순 타입만 쓴다.
- 기존 테스트를 지우거나 비활성화하지 마라.
