# Step 0: booking-domain

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `docs/rules/backend.md` § Kotlin, § 헥사고날, § 테스트
- `docs/design.md` § 4. 코어 모듈, § 6.2 좌석 선점, § 6.3 예매 상태 전이, § 6.4 결제 (`rg -n '^##' docs/design.md`로 줄을 찾는다)
- `docs/adr/README.md`, `docs/templates/adr.md` (새 ADR 형식)
- `core/src/test/kotlin/io/github/ticketing/HexagonalArchitectureTests.kt`
- `core/src/main/kotlin/io/github/ticketing/catalog/ScheduleRegistered.kt` (좌석 사본의 원천, 참조만)

## 작업

booking 헥사고날 모듈의 **순수 도메인**을 만들고, 사용자와 합의한 M1 설계 세부를 문서에 반영한다.

### 1. 합의된 설계 세부 (이 phase의 모든 step이 따르는 전제)

- **API 흐름**: `POST /api/v1/bookings`가 Redis 선점과 `PENDING` 생성을 한다. `POST /api/v1/bookings/{id}/payments`가 결제 요청 이벤트를 발행한다.
- **좌석 사본**: booking은 catalog의 `ScheduleRegistered`를 받아 `booking.schedule`과 `booking.schedule_seat`에 회차와 좌석·가격 사본을 둔다. catalog를 직접 조회하지 않는다.
- **이벤트 소유 방향**: 결제 계약 이벤트(`PaymentRequested`, `RefundRequested`, `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded`)는 모두 `payment` 모듈 루트에 둔다. 의존 방향은 `booking → payment` 하나다. 이유: 양쪽이 각자 이벤트를 소유하면 booking ↔ payment 순환이 생겨 `ApplicationModules.verify()`가 실패한다. payment는 예매를 모르고 `orderId`만 안다.
- **결제 실패**: `PENDING → CANCELLED`(사유 `PAYMENT_FAILED`)로 바꾸고 좌석을 반환한다. 재결제는 없다.
- **만료는 지연 판정**: 예매는 `expiresAt = 생성 시각 + 5분`을 갖는다. 결제 요청이나 결제 결과가 도착했을 때 `Clock`과 비교해 `EXPIRED`로 전환한다. 일괄 정리는 M2 Batch가 한다.
- **만료 후 결제 성공, 확정 시 이미 판매된 좌석**: 상태를 `EXPIRED` 또는 `CANCELLED`(`SEAT_ALREADY_SOLD`)로 바꾸고 `RefundRequested`를 발행한다.
- **확정 예매 취소**: 즉시 `CANCELLED`(`USER_REQUESTED`)로 바꾸고 `RefundRequested`를 발행한다. 환불은 비동기다.
- **유니크 방어선**: 확정 시에만 행을 넣고 취소 시 지우는 `booking.sold_seat (schedule_id, seat_id)` 테이블에 유니크 제약을 둔다. 이유: 예매 좌석 테이블에 걸면 취소·만료된 예매가 좌석을 계속 점유한다.

### 2. 도메인 (`io.github.ticketing.booking.domain`, 순수 Kotlin)

```kotlin
@JvmInline value class BookingId(val value: Long)
@JvmInline value class ScheduleId(val value: Long)
@JvmInline value class SeatId(val value: Long)
@JvmInline value class MemberId(val value: Long)
@JvmInline value class HoldToken(val value: String)   // 선점 소유자 식별자(UUID 문자열)

enum class BookingStatus { PENDING, CONFIRMED, EXPIRED, CANCELLED }
enum class CancelReason { USER_REQUESTED, PAYMENT_FAILED, SEAT_ALREADY_SOLD }

class Booking /* id는 저장 전 null 허용 */ {
    companion object {
        const val MAX_SEATS = 4
        val HOLD_DURATION: Duration = Duration.ofMinutes(5)
        fun create(memberId, scheduleId, seats: List<BookedSeat>, holdToken, now: Instant): Booking
    }
    fun isExpired(now: Instant): Boolean
    fun requestPayment(now: Instant)                 // PENDING·미만료만 가능, paymentRequestedAt 기록(재요청은 no-op)
    fun onPaymentSucceeded(now: Instant): PaymentOutcome
    fun onPaymentFailed(now: Instant)                // PENDING → CANCELLED(PAYMENT_FAILED), 그 외는 no-op
    fun failConfirmation(now: Instant)               // PENDING → CANCELLED(SEAT_ALREADY_SOLD)
    fun expire(now: Instant)                         // PENDING·만료만 가능
    fun cancelByUser(memberId, now: Instant): CancelOutcome
}
data class BookedSeat(val seatId: SeatId, val price: BigDecimal)

sealed interface PaymentOutcome {           // 결제 성공 이벤트를 받았을 때
    data object Confirmed : PaymentOutcome
    data object AlreadyConfirmed : PaymentOutcome     // 중복 이벤트
    data class RefundRequired(val reason: ...) : PaymentOutcome  // 만료됨, 이미 취소됨 등
}
sealed interface CancelOutcome { /* ReleasedHold(PENDING 취소), RefundRequired(CONFIRMED 취소) */ }
```

- 시그니처는 방향만 제시한 것이다. 이름은 재량으로 정하되 아래 규칙은 반드시 지킨다.
  - 좌석은 1~4개이고 중복이 없으며 같은 회차에 속한다. 총액은 좌석 가격의 합이다.
  - `onPaymentSucceeded`: `PENDING`이고 미만료면 `CONFIRMED`가 된다. `PENDING`이지만 만료됐으면 `EXPIRED`가 되고 `RefundRequired`를 반환한다. 이미 `CONFIRMED`면 `AlreadyConfirmed`, `EXPIRED`나 `CANCELLED`면 `RefundRequired`다.
  - 멱등: 같은 결제 결과가 두 번 와도 상태가 다시 바뀌지 않는다.
  - 본인 예매가 아니면 취소할 수 없다.
- 도메인 규칙 위반은 **순수 Kotlin 예외**(`BookingRuleException(code: String)` 계열, 코드 예: `TOO_MANY_SEATS`, `BOOKING_EXPIRED`, `INVALID_BOOKING_STATE`, `NOT_BOOKING_OWNER`)로 던진다. HTTP 상태와 `DomainException` 변환은 이후 step의 `application`이 맡는다. 이유: `DomainException`이 Spring `HttpStatus`에 의존해 ArchUnit의 "도메인은 Spring 비의존" 규칙에 걸린다.
- `when`에 `else`를 두지 않는다.

### 3. 테스트 (`core/src/test/kotlin/io/github/ticketing/booking/domain/`)

프레임워크 없는 순수 단위 테스트를 쓴다. 상태 전이의 허용 경로와 금지 경로를 모두 다루고, 이름은 한국어 백틱으로 짓는다. 시각은 고정 `Instant`를 쓴다.

### 4. 문서

- `docs/design.md`: 1절의 세부를 해당 절에 반영한다. § 4 모듈 규칙에 이벤트 소유 방향을, § 5에 booking 좌석 사본과 `sold_seat`를, § 6.3/6.4에 결제 실패·만료 지연 판정·자동 환불 조건을 적는다. 문구에 `PAYMENT_FAILED`, `sold_seat`가 들어가게 한다.
- `docs/adr/0017-payment-owns-payment-events.md`: 결제 이벤트 계약을 payment가 소유하는 결정이다(대안: 각자 소유하고 순환 허용, 공용 이벤트 모듈). 템플릿 형식을 따르고 `docs/adr/README.md` 목록에 추가한다.
- `docs/roadmap.md` M1-3의 "도메인 모델과 상태 전이"를 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.booking.domain.*" --tests "io.github.ticketing.HexagonalArchitectureTests"
rg -q 'PAYMENT_FAILED' docs/design.md
rg -q 'sold_seat' docs/design.md
test -n "$(fd '^0017-' docs/adr)"
rg -q '^- \[x\] 도메인 모델과 상태 전이' docs/roadmap.md
```

## 검증 절차

1. 위 AC를 실행하고, 실패하면 원인을 수정한다. 문서를 고쳤으면 `./scripts/agent/docs/check-docs.sh docs/design.md docs/adr`도 실행한다.
2. `phases/2-booking/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `booking.domain`에서 Spring, JPA, jOOQ, `io.github.ticketing.DomainException`, catalog·payment 타입을 import하지 마라. 이유: 도메인은 순수 Kotlin이고 ArchUnit이 검사한다.
- 영속성, Redis, 컨트롤러, 이벤트 클래스를 만들지 마라. 이유: step 1~4의 범위다.
- 1절의 합의를 바꾸지 마라. 문서와 다르게 가야 할 이유가 생기면 `blocked`로 보고하라. 이유: 설계 변경은 사용자 합의가 먼저다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
