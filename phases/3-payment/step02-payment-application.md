# Step 2: payment-application

## 목표와 참조

결제 요청·결과 반영·환불 유스케이스를 만든다. 입력 어댑터는 다음 step에서 연결한다. 코딩·테스트 규칙은 `docs/rules/backend.md`를 따른다.

- step 0의 `core/src/main/kotlin/io/github/ticketing/payment/domain/Payment.kt`, `application/port/required/PaymentCommandPort.kt`.
- step 1의 `core/src/main/kotlin/io/github/ticketing/payment/application/port/required/PgPort.kt`와 요청/결과 타입.
- `core/src/main/kotlin/io/github/ticketing/payment/`의 루트 이벤트 5종.
- `docs/adr/0018-payment-retry-boundaries.md` § 결정.

## 입력 계약과 트랜잭션

`payment.application.port.provided`에 이벤트·HTTP DTO와 분리된 인터페이스와 command를 둔다.

```kotlin
interface RequestPaymentUseCase { fun request(command: RequestPaymentCommand) }
interface RequestRefundUseCase { fun request(command: RequestRefundCommand) }
interface HandlePaymentWebhookUseCase { fun handle(command: PaymentWebhookCommand) }
enum class PaymentWebhookType { PAYMENT_APPROVED, PAYMENT_FAILED, REFUND_COMPLETED }
```

- RequestPaymentCommand: orderId Long, memberId Long, amount BigDecimal, idempotencyKey String, requestedAt Instant.
- RequestRefundCommand: orderId Long, reason String, requestedAt Instant.
- PaymentWebhookCommand: eventId String, type PaymentWebhookType, transactionId String, orderId Long, amount BigDecimal, occurredAt Instant, failureReason String?.

`PaymentRequestService`와 `PaymentRefundService`는 트랜잭션 없는 조율자다. DB 작업은 별도 Bean인 `PaymentTransactionService`의 @Transactional 메서드로 분리한다. self-invocation으로 트랜잭션을 적용하려 하지 않는다. PG 호출 중 활성 트랜잭션과 DB 락이 없어야 한다.

## 결제 요청

1. 트랜잭션 A: 주문으로 기존 결제를 찾거나 REQUESTED로 저장한다. 같은 주문의 회원·금액·키가 달라지거나 키가 다른 주문에 쓰였으면 계약 충돌이다. 확정된 상태 또는 거래 ID가 붙은 REQUESTED면 PG 호출을 생략한다.
2. 트랜잭션 밖: 기존 기록의 동일 키·본문으로 PG를 호출한다.
3. 트랜잭션 B: 최신 행을 다시 읽는다. Accepted는 거래 ID만 붙인다. Rejected는 아직 REQUESTED일 때만 FAILED와 PaymentFailed를 함께 저장·발행한다. 먼저 도착한 웹훅 결과를 덮어쓰지 않는다.
4. Unknown: REQUESTED를 유지하고 식별자·실패 분류를 로그에 남긴 뒤 예외를 전파한다. 이후 리스너가 미완료 이벤트로 남길 수 있어야 한다.

최초 생성 유니크 충돌·버전 충돌은 전파한다. 실패한 트랜잭션에서 잡아 재조회하지 않는다. 재처리가 새 트랜잭션에서 기존 행을 읽게 한다. 동시 처리에서는 PG 호출이 여럿이어도 같은 키로 거래는 하나다.

## 웹훅 결과 반영

`PaymentWebhookService`는 다음 작업을 하나의 트랜잭션에서 처리한다.

- recordWebhookEvent가 false면 성공 종료한다. 거래 ID로 조회하고 없으면 주문 ID로 조회한다.
- 기존 거래 ID·주문·금액과 일치하는지 확인하고, 비어 있는 거래 ID는 붙인다. 미등록 결제는 PAYMENT_NOT_FOUND, 식별자·금액 불일치는 PAYMENT_CONTRACT_MISMATCH다.
- 타입별 도메인 전이를 실행한다. Applied일 때만 PaymentSucceeded/PaymentFailed/PaymentRefunded를 발행한다. AlreadyApplied여도 새로 붙인 거래 ID는 저장한다.
- 결과 시각은 command.occurredAt, 수신 기록 시각은 Clock에서 얻는다. 실패 웹훅에는 비어 있지 않은 failureReason이 필요하다.
- 미등록 거래·계약/상태 충돌·락 충돌이면 중복 기록까지 롤백한다. 실패한 웹훅을 처리 완료로 남기지 않는다.

## 환불

1. 트랜잭션 A: SUCCEEDED면 REFUND_REQUESTED로 저장한다. REFUND_REQUESTED는 재호출을 준비하고 REFUNDED는 종료한다. 나머지 상태는 충돌이다.
2. 트랜잭션 밖: 저장된 거래 ID와 `refund-{orderId}` 키로 PG를 호출한다. 거래 ID가 없으면 오류를 전파한다.
3. Accepted면 완료 웹훅을 기다린다. Rejected·Unknown이면 REFUND_REQUESTED를 유지하고 오류를 전파한다. 먼저 도착한 완료 웹훅의 상태는 되돌리지 않는다.

REFUND_REQUESTED라는 이유로 재호출을 막지 않는다. 응답 유실·로컬 장애 뒤에도 같은 키로 복구해야 한다. 환불 실패 상태·주기적 재처리 스케줄러·대사는 추가하지 않는다.

## 테스트와 AC

출력 포트 fake로 유스케이스를 검증하고, 트랜잭션·롤백·이벤트 원자성은 PostgreSQL 통합 테스트로 검증한다.

- 순차 중복·계약 충돌, Accepted/Rejected/Unknown, Unknown 이후 재호출 성공.
- PG 호출 시 트랜잭션이 없고 준비 기록이 커밋되어 있음.
- 응답 전 웹훅 도착, 지연 Accepted/Rejected가 성공 상태를 덮어쓰지 않음.
- 같은/다른 eventId의 동일 결과, 환불 후 승인 재수신, 실패 웹훅의 같은 ID 재처리.
- 환불 Unknown/Rejected 이후 재호출, REFUNDED에서는 호출 생략.

```bash
test -f core/src/main/kotlin/io/github/ticketing/payment/application/service/PaymentRequestService.kt
test -f core/src/main/kotlin/io/github/ticketing/payment/application/service/PaymentRefundService.kt
test -f core/src/main/kotlin/io/github/ticketing/payment/application/service/PaymentWebhookService.kt
./scripts/agent/test/run-tests.sh
```

## 금지사항과 완료 기록

- PG 호출과 DB 트랜잭션을 겹치게 하지 않는다. 이유: 커넥션·락 장기 점유와 교착을 막는다.
- Unknown을 PaymentFailed로 바꾸거나 환불 거절을 완료 처리하지 않는다. 이유: 외부 결제와 로컬 상태가 어긋날 수 있다.
- booking 타입 import·이벤트 계약 변경·입력 어댑터 추가는 하지 않는다.

AC 통과 후에만 `phases/3-payment/index.json`의 step 2 상태와 200자 이내 summary를 갱신한다. 실패는 error·error_message, 사용자 결정 필요는 blocked·blocked_reason이다. 로드맵 완료 처리와 직접 커밋은 하지 않는다.
