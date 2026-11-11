# Step 0: payment-domain-persistence

## 목표와 참조

payment 모듈의 도메인, 영속성 출력 포트와 어댑터를 만든다. PG 호출·서비스·입력 어댑터는 이후 step의 범위다. 코딩·테스트 규칙은 `docs/rules/backend.md`를 따른다.

필요한 부분만 읽는다:

- `core/src/main/kotlin/io/github/ticketing/payment/`의 루트 계약 이벤트 5종. 시그니처를 보존한다.
- `core/src/main/kotlin/io/github/ticketing/booking/adapter/required/jpa/BookingPersistenceAdapter.kt`, `BookingJpaMapper.kt`, `BookingJpaEntity.kt`: 저장·복원·버전 처리 예시.
- `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt`, `TestcontainersConfiguration.kt`.
- `docs/rules/backend.md` § 헥사고날, § 영속성.

## 도메인 계약

`payment.domain`은 순수 Kotlin이다. 식별자 `PaymentId`, `OrderId`, `MemberId`는 각각 양수 Long의 value class로 만든다.

```kotlin
enum class PaymentStatus { REQUESTED, SUCCEEDED, FAILED, REFUND_REQUESTED, REFUNDED }
sealed interface TransitionResult {
    data object Applied : TransitionResult
    data object AlreadyApplied : TransitionResult
}
```

`Payment`의 인터페이스:

- `request(orderId: OrderId, memberId: MemberId, amount: BigDecimal, idempotencyKey: String, now: Instant): Payment`
- `restore(...)`: 저장 필드 전체와 `version` 복원. 신규 객체의 `id`는 null.
- `attachPgTransaction(transactionId: String)`: 최초 부착, 같은 값은 no-op, 다른 값은 계약 충돌.
- `approve(amount: BigDecimal, now: Instant): TransitionResult`: REQUESTED → SUCCEEDED.
- `fail(reason: String, now: Instant): TransitionResult`: REQUESTED → FAILED.
- `requestRefund(now: Instant): TransitionResult`: SUCCEEDED → REFUND_REQUESTED.
- `completeRefund(now: Instant): TransitionResult`: REFUND_REQUESTED → REFUNDED.

재수신은 AlreadyApplied를 반환한다. 승인 재수신은 SUCCEEDED·REFUND_REQUESTED·REFUNDED, 실패는 FAILED, 환불 요청은 REFUND_REQUESTED·REFUNDED, 환불 완료는 REFUNDED에서 멱등이다. 그 밖의 전이는 `INVALID_PAYMENT_STATE` 순수 Kotlin 예외다. 기존 시각과 상태를 덮어쓰지 않는다.

금액은 양수·정수 원 단위·numeric(12,0) 범위다. 금액 비교는 compareTo로 하고 승인 금액 불일치는 `PAYMENT_CONTRACT_MISMATCH`로 거부한다. 멱등 키는 비어 있지 않은 최대 100자다. 도메인 예외가 Spring HTTP 타입을 참조하지 않게 한다.

## 영속성 계약

`core/src/main/resources/db/migration/V6__create_payment_tables.sql`을 추가한다.

| 테이블 | 필드와 제약 |
|---|---|
| `payment.payment` | id bigint identity PK, order_id·member_id bigint, amount numeric(12,0), status, idempotency_key varchar(100), nullable pg_transaction_id varchar(100), nullable failure_reason, requested_at, nullable approved_at·failed_at·refund_requested_at·refunded_at, version. 시각은 timestamptz. 주문·멱등 키·거래 ID에 각각 UNIQUE. 금액 양수·상태 CHECK. |
| `payment.webhook_event` | event_id varchar(64) PK, type, transaction_id varchar(100), received_at timestamptz. 모두 NOT NULL. |

`payment.application.port.required.PaymentCommandPort`:

```kotlin
interface PaymentCommandPort {
    fun save(payment: Payment): Payment
    fun findByOrderId(orderId: OrderId): Payment?
    fun findByIdempotencyKey(key: String): Payment?
    fun findByPgTransactionId(transactionId: String): Payment?
    fun recordWebhookEvent(eventId: String, type: String, transactionId: String, now: Instant): Boolean
}
```

`payment.adapter.required.jpa.PaymentPersistenceAdapter`와 엔티티·Spring Data 리포지토리·매퍼를 만든다. save는 저장 ID와 최신 버전을 반환한다. @Version과 도메인이 읽은 버전을 보존·검사한다. 같은 주문의 동시 최초 생성은 UNIQUE가 방어하고, 충돌 예외는 호출자에 전파한다.

웹훅 기록은 엔티티 없이 생성된 jOOQ DSL의 ON CONFLICT (event_id) DO NOTHING으로 구현한다. 신규면 true, 중복이면 false다. 어댑터가 자체 트랜잭션을 열지 않고 호출자의 트랜잭션에 참여한다.

## 테스트와 AC

- `PaymentTests`: 허용·금지·중복 전이, 환불 후 승인 재수신, 금액·키·ID 검증과 거래 ID 충돌.
- `PaymentPersistenceTests`: 저장 왕복, 각 유니크 제약, 버전 충돌, 웹훅 중복 false, 롤백 후 같은 eventId 재기록.
- 테스트 빌드가 마이그레이션에 따라 generateJooq를 실행하는지 확인한다.

```bash
test -f core/src/main/resources/db/migration/V6__create_payment_tables.sql
test -f core/src/main/kotlin/io/github/ticketing/payment/adapter/required/jpa/PaymentPersistenceAdapter.kt
./scripts/agent/test/run-tests.sh
```

## 금지사항과 완료 기록

- booking 내부 타입 import·스키마 간 FK·기존 이벤트 시그니처 변경을 하지 않는다. 이유: booking → payment 단방향 계약을 보존한다.
- PG 클라이언트·서비스·입력 어댑터를 추가하지 않는다. 이유: 이후 step의 범위다.
- 생성 코드를 수정하거나 커밋하지 않는다.

AC 통과 후에만 `phases/3-payment/index.json`의 step 0을 completed로 바꾸고 경로·결론을 200자 이내 summary로 남긴다. 실패는 error·error_message, 사용자 결정 필요는 blocked·blocked_reason으로 기록한다. 로드맵 완료 처리와 직접 커밋은 하지 않는다.
