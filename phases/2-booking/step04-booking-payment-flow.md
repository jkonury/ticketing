# Step 4: booking-payment-flow

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- step 0~3에서 만든 `core/src/main/kotlin/io/github/ticketing/booking/` 전체
- `core/src/main/java/io/github/ticketing/payment/package-info.java`
- `docs/rules/backend.md` § 코어 모듈 구조, § 트랜잭션과 동시성, § 로깅

## 작업

예매와 결제를 이벤트로 잇는다. payment 모듈의 구현은 다음 phase에서 만들고, 이번 step은 **결제 계약 이벤트 타입**과 booking 측의 처리만 만든다.

### 1. 결제 계약 이벤트 (`io.github.ticketing.payment` 루트, 파일 하나에 하나씩)

```kotlin
// booking → payment
data class PaymentRequested(val orderId: Long, val memberId: Long, val amount: BigDecimal, val idempotencyKey: String, val requestedAt: Instant)
data class RefundRequested(val orderId: Long, val reason: String, val requestedAt: Instant)
// payment → (구독자)
data class PaymentSucceeded(val paymentId: Long, val orderId: Long, val amount: BigDecimal, val paidAt: Instant)
data class PaymentFailed(val paymentId: Long, val orderId: Long, val reason: String, val failedAt: Instant)
data class PaymentRefunded(val paymentId: Long, val orderId: Long, val refundedAt: Instant)
```

- `orderId`는 bookingId다. payment는 이 값의 의미를 모른다.
- PG 멱등 키는 예매 하나에 결제 하나가 되도록 `"booking-{bookingId}"`로 고정한다.
- 이벤트 소유 방향은 ADR 0017을 따른다. 의존 방향은 `booking → payment` 하나이고, payment가 booking 타입을 참조하면 안 된다.

### 2. booking 이벤트 (`io.github.ticketing.booking` 루트)

```kotlin
data class BookingConfirmed(val bookingId: Long, val memberId: Long, val scheduleId: Long, val seatIds: List<Long>, val amount: BigDecimal, val confirmedAt: Instant)
data class BookingCancelled(val bookingId: Long, val memberId: Long, val scheduleId: Long, val reason: String, val refundRequired: Boolean, val cancelledAt: Instant)
data class BookingExpired(val bookingId: Long, val memberId: Long, val scheduleId: Long, val expiredAt: Instant)
```

상태 전이를 저장하는 트랜잭션 안에서 발행한다. notification과 settlement가 이 이벤트를 구독한다.

### 3. 결제 요청 `POST /api/v1/bookings/{id}/payments` (`hasRole('USER')`)

- 헤더 `Idempotency-Key` 필수, 응답 202 `{bookingId, status}`
- 본인 예매인지 확인한다. 만료됐으면 `EXPIRED`로 저장하고 커밋 후 선점을 해제한 뒤 409 `BOOKING_EXPIRED`
- `PENDING`이고 아직 요청하지 않았으면 `requestPayment` 후 `PaymentRequested`를 발행한다. 이미 요청했으면 다시 발행하지 않고 202를 반환한다(멱등).

### 4. 결제 결과 리스너 (`booking.adapter.provided.event`, `@ApplicationModuleListener`)

| 이벤트 | 처리 |
|---|---|
| `PaymentSucceeded` | `onPaymentSucceeded`의 결과에 따른다. **Confirmed**: 같은 트랜잭션에서 `insertSoldSeats`를 호출한다. 삽입 수가 좌석 수와 같으면 `CONFIRMED`로 저장하고 `BookingConfirmed`를 발행한 뒤, 커밋 후 `markSold`를 호출한다. 부족하면 예외로 롤백하고, **새 트랜잭션에서** `failConfirmation`(`CANCELLED`, `SEAT_ALREADY_SOLD`)과 `RefundRequested`, `BookingCancelled`를 처리한 뒤 커밋 후 `release`를 호출한다. **AlreadyConfirmed**: 아무것도 하지 않는다. **RefundRequired**: 필요하면 `EXPIRED`로 저장하고 `RefundRequested`(+`BookingExpired`)를 발행한 뒤 커밋 후 `release`를 호출한다 |
| `PaymentFailed` | `PENDING`이면 `CANCELLED(PAYMENT_FAILED)`로 저장하고 `BookingCancelled`를 발행한 뒤 커밋 후 `release`. 그 외 상태면 no-op |
| `PaymentRefunded` | 로그만 남긴다(`bookingId` 포함) |

- 리스너는 유스케이스(`port.provided`)만 호출한다. 판단 로직은 도메인에 있다.
- 같은 이벤트가 다시 와도 결과가 같아야 한다. 낙관적 락 충돌(`OptimisticLockingFailureException`)은 다시 던져 이벤트 발행 레지스트리가 재시도하게 한다. 로그만 남기고 삼키지 않는다.
- **Redis 반영은 항상 DB 커밋 후에 한다.** 이유: 커밋이 실패했는데 Redis가 이미 `SOLD`면 팔리지 않은 좌석이 판매 불가로 남는다. 반대로 커밋 후 Redis 반영이 실패하면 DB 유니크 제약이 중복 판매를 막고, 남은 선점 키는 TTL로 정리된다.
- 로그에 `bookingId`와 `paymentId`를 포함한다.

### 5. 확정 예매 취소

step 3의 `POST /api/v1/bookings/{id}/cancel`을 확장한다. `CONFIRMED`면 `CANCELLED(USER_REQUESTED)`로 바꾸고 `deleteSoldSeats`를 호출한 뒤 `RefundRequested`와 `BookingCancelled(refundRequired=true)`를 발행한다. 커밋 후에는 `markAvailable`을 호출한다.

### 6. 테스트 (`core/src/test/kotlin/io/github/ticketing/booking/`)

payment 구현이 아직 없으므로 테스트가 `PaymentSucceeded`, `PaymentFailed`를 직접 발행한다(트랜잭션 안에서 `ApplicationEventPublisher`, 또는 Modulith `Scenario`). 비동기 처리는 Awaitility로 기다린다. 시각은 테스트용 가변 `Clock` 빈(`@Primary`)으로 조작한다.

- 결제 요청 시 `PaymentRequested` 1건이 발행되고, 재요청해도 1건이다(`PublishedEvents` 또는 `@RecordApplicationEvents`).
- 성공하면 `CONFIRMED`와 `sold_seat` 행, Redis `SOLD`, `BookingConfirmed`가 생긴다. 같은 성공 이벤트가 두 번 와도 결과가 같다.
- 실패하면 `CANCELLED(PAYMENT_FAILED)`가 되고 좌석을 다시 선점할 수 있다.
- 만료 후 성공이 오면 `EXPIRED`와 `RefundRequested`가 발행된다.
- 다른 예매가 이미 `sold_seat`를 가진 좌석이면 `CANCELLED(SEAT_ALREADY_SOLD)`와 `RefundRequested`가 발행되고 500이 나지 않는다.
- 확정 예매를 취소하면 `sold_seat`가 삭제되고 `RefundRequested`가 발행되며 Redis가 `AVAILABLE`이 된다.

### 7. 로드맵

`docs/roadmap.md` M1-3의 "예매 생성(`PENDING`) → 결제 요청 이벤트 발행", "결제 결과 이벤트 수신 → 확정 또는 좌석 반환", "사용자 취소", "좌석 상태 변경을 Redis Streams로 발행, 좌석 스냅샷 갱신"을 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
test -f core/src/main/kotlin/io/github/ticketing/payment/PaymentRequested.kt
test -f core/src/main/kotlin/io/github/ticketing/booking/BookingConfirmed.kt
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.booking.*" --tests "io.github.ticketing.ModularityTests"
rg -q '^- \[x\] 결제 결과 이벤트 수신' docs/roadmap.md
rg -q '^- \[x\] 사용자 취소' docs/roadmap.md
./scripts/agent/test/check-summary.sh
```

## 검증 절차

1. booking과 Modularity 테스트를 먼저 통과시키고, 마지막에 전체 check를 실행한다.
2. `phases/2-booking/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- payment 모듈에 이벤트 타입 외의 코드(리스너, 서비스, 테이블)를 만들지 마라. 이유: 다음 phase의 범위다.
- 결제 결과 이벤트를 booking 패키지에 정의하지 마라. 이유: booking ↔ payment 순환 의존(ADR 0017)
- 커밋 전에 Redis `markSold`/`release`/`markAvailable`을 호출하지 마라. 이유: 위 4절
- 유니크 위반 예외를 500으로 흘려보내지 마라. 이유: "이미 판매됨"으로 변환해 처리한다.
- `@Scheduled`로 만료 예매를 일괄 정리하지 마라. 이유: 합의한 대로 M1은 지연 판정만 하고, 일괄 정리는 M2 Batch가 한다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
