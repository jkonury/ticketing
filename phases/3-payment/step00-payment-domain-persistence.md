# Step 0: payment-domain-persistence

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- `core/src/main/kotlin/io/github/ticketing/payment/*.kt` (2-booking phase에서 만든 결제 계약 이벤트: `PaymentRequested`, `RefundRequested`, `PaymentSucceeded`, `PaymentFailed`, `PaymentRefunded`)
- 헥사고날 구조 예시: `core/src/main/kotlin/io/github/ticketing/booking/` (`domain`, `application.port.*`, `adapter.required.jpa`의 매퍼 형식)
- `docs/rules/backend.md` § 헥사고날, § 영속성
- `docs/adr/0017-payment-owns-payment-events.md`

## 작업

payment 헥사고날 모듈의 도메인과 영속성을 만든다. PG 연동과 이벤트 처리는 step 1에서 한다.

### 1. 도메인 (`io.github.ticketing.payment.domain`, 순수 Kotlin)

```kotlin
@JvmInline value class PaymentId(val value: Long)
@JvmInline value class OrderId(val value: Long)
enum class PaymentStatus { REQUESTED, SUCCEEDED, FAILED, REFUND_REQUESTED, REFUNDED }

class Payment {
    companion object { fun request(orderId, memberId, amount, idempotencyKey, now): Payment }
    fun attachPgTransaction(transactionId: String)            // 이미 같은 값이면 no-op, 다른 값이면 예외
    fun approve(amount: BigDecimal, now): TransitionResult    // REQUESTED → SUCCEEDED. 금액 불일치는 예외
    fun fail(reason: String, now): TransitionResult           // REQUESTED → FAILED
    fun requestRefund(now): TransitionResult                  // SUCCEEDED → REFUND_REQUESTED
    fun completeRefund(now): TransitionResult                 // REFUND_REQUESTED(또는 SUCCEEDED) → REFUNDED
}
sealed interface TransitionResult { data object Applied; data object AlreadyApplied }
```

- 같은 전이를 다시 요청하면 `AlreadyApplied`를 반환하고 상태를 바꾸지 않는다(웹훅 중복 대비).
- 허용되지 않는 전이(예: `FAILED → SUCCEEDED`)는 순수 Kotlin 예외를 던진다. 코드는 `INVALID_PAYMENT_STATE`이고, 로그와 경보 대상이다.
- 환불 요청이 승인보다 먼저 오는 경우는 없다. booking은 `PaymentSucceeded`를 받은 뒤에만 `RefundRequested`를 보낸다. 그래도 `REQUESTED`에서 `requestRefund`가 오면 예외를 던진다.

### 2. Flyway `V6__create_payment_tables.sql` (`payment` 스키마)

| 테이블 | 컬럼 | 제약 |
|---|---|---|
| `payment.payment` | `id` identity, `order_id`, `member_id`, `amount numeric(12,0)`, `status`, `idempotency_key varchar(100)`, `pg_transaction_id varchar(100)` null, `failure_reason` null, `requested_at`, `approved_at`, `failed_at`, `refund_requested_at`, `refunded_at`, `version` | `UNIQUE (order_id)`, `UNIQUE (idempotency_key)`, `UNIQUE (pg_transaction_id)` |
| `payment.webhook_event` | `event_id varchar(64) PK`, `type`, `transaction_id`, `received_at` | 웹훅 중복 제거용 |

### 3. 출력 포트와 JPA 어댑터

```kotlin
interface PaymentCommandPort {
    fun save(payment: Payment): Payment
    fun findByOrderId(orderId: OrderId): Payment?
    fun findByPgTransactionId(transactionId: String): Payment?
    /** INSERT ... ON CONFLICT DO NOTHING. 처음 받은 eventId면 true */
    fun recordWebhookEvent(eventId: String, type: String, transactionId: String, now: Instant): Boolean
}
```

- 엔티티와 도메인을 분리하고 매퍼로 변환한다. `@Version`을 보존한다.
- `recordWebhookEvent`가 `ON CONFLICT`를 쓰는 이유: PostgreSQL에서 유니크 위반이 나면 트랜잭션이 abort 상태가 되어 이후 쓰기가 막힌다.

### 4. 테스트

- `core/src/test/kotlin/io/github/ticketing/payment/domain/`: 전이 허용·금지·멱등(순수 단위)
- `core/src/test/kotlin/io/github/ticketing/payment/adapter/required/jpa/`: 저장 왕복, `order_id` 유니크, `recordWebhookEvent` 중복 시 false

## Acceptance Criteria

```bash
test -f core/src/main/resources/db/migration/V6__create_payment_tables.sql
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.payment.*" --tests "io.github.ticketing.HexagonalArchitectureTests"
```

## 검증 절차

1. 위 AC를 실행하고, 실패하면 원인을 수정한다.
2. `phases/3-payment/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `io.github.ticketing.booking` 패키지의 어떤 타입도 import하지 마라. 이유: 의존 방향은 `booking → payment` 하나다(ADR 0017). 반대 방향이 생기면 순환이 된다.
- payment 루트의 이벤트 클래스 시그니처를 바꾸지 마라. 이유: booking이 이미 이 계약으로 구현되어 있다.
- `booking` 스키마로 FK를 만들지 마라. 이유: 설계 규칙 2
- PG HTTP 클라이언트, 리스너, 웹훅 컨트롤러를 만들지 마라. 이유: step 1의 범위다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
