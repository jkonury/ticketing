# Step 3: payment-inbound-adapters

## 목표와 참조

결제·환불 리스너와 서명 검증 웹훅 API를 연결한다. 코딩·테스트 규칙은 `docs/rules/backend.md`를 따른다.

- step 2의 `core/src/main/kotlin/io/github/ticketing/payment/application/port/provided/` 및 서비스 오류 계약.
- step 1의 `core/src/main/kotlin/io/github/ticketing/payment/adapter/required/pg/PaymentPgConfiguration.kt`와 설정 타입.
- `core/src/main/kotlin/io/github/ticketing/payment/` 루트 이벤트 5종.
- `core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt`, `core/src/main/kotlin/io/github/ticketing/ApiExceptionHandler.kt`, `DomainException.kt`.
- `fake-pg/src/main/kotlin/io/github/ticketing/fakepg/payment/service/WebhookSender.kt`: 본문·서명 규약만 확인한다.

## 이벤트 입력

`payment.adapter.provided.event.PaymentRequestListener`, `RefundRequestListener`는 이벤트를 command로 바꾸고 유스케이스를 호출한다.

- 모두 `@ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)`를 사용한다. 기본 REQUIRES_NEW를 쓰면 PG 호출까지 트랜잭션에 들어간다.
- 실패 예외를 삼키지 않는다. 레지스트리에 미완료 발행으로 남겨야 한다.
- [Modulith 2.1.1 API](https://docs.spring.io/spring-modulith/docs/current/api/org/springframework/modulith/events/ApplicationModuleListener.html)와 [레지스트리 공식 문서](https://docs.spring.io/spring-modulith/reference/events.html)를 확인한다. 실제 리스너의 PG 호출에 트랜잭션이 없고 성공/실패가 레지스트리에 완료/미완료로 남는지 검증한다.

기존 booking 테스트는 결제 결과를 직접 발행한다. 해당 테스트 컨텍스트에서 두 payment 입력 리스너를 테스트 전용 mock으로 대체해 외부 PG 호출과 미등록 환불을 막는다. booking의 상태·이벤트 단언은 유지한다. payment 통합 테스트는 실제 리스너를 사용한다. main에 리스너 비활성화 옵션은 추가하지 않는다.

## 웹훅 HTTP 입력

`payment.adapter.provided.web.PaymentWebhookController`:

```text
POST /api/v1/payments/webhooks/fake-pg
X-FakePg-Timestamp: epoch 초
X-FakePg-Signature: hex HMAC-SHA256(secret, timestamp + "." + rawBody)
```

본문: `{eventId, type, transactionId, orderId: string, amount: integer, occurredAt, failureReason}`. 타입은 PAYMENT_APPROVED/PAYMENT_FAILED/REFUND_COMPLETED다.

- `PaymentWebhookSignatureVerifier`는 timestamp 텍스트·점·원문 바이트로 HMAC을 계산한다. hex를 디코딩한 서명과 MessageDigest.isEqual로 비교한다.
- 누락·잘못된 헤더/hex·과거/미래로 허용 오차를 넘은 timestamp는 401 INVALID_WEBHOOK_SIGNATURE다. Clock을 사용하고 시각 변환·연산 오버플로도 안전하게 거부한다.
- 서명 검증 후에만 JSON을 파싱한다. 잘못된 JSON/타입/필수 값·양수가 아닌 주문/금액·길이 초과는 400 INVALID_PAYMENT_WEBHOOK다. eventId 최대 64자, 거래 ID 최대 100자다. orderId는 양수 Long, amount는 정수 원 단위 BigDecimal로 변환한다.
- 정상·중복은 200, 미등록 거래는 404 PAYMENT_NOT_FOUND, 식별자/금액 충돌은 409 PAYMENT_CONTRACT_MISMATCH, 전이 충돌은 409 INVALID_PAYMENT_STATE다. 락 충돌은 기존 409 CONCURRENT_MODIFICATION을 유지한다.
- HTTP 매핑은 기존 루트 ApiExceptionHandler에 추가한다. 순수 payment 예외의 HTTP 매핑을 위해 도메인에 Spring 의존을 넣거나 새 RestControllerAdvice를 만들지 않는다.
- SecurityConfig는 정확히 이 경로의 POST만 permitAll로 추가한다. 로그에는 식별자·오류 분류만 남기고 secret·서명·본문 전체는 남기지 않는다.

## 테스트와 AC

- `PaymentWebhookHttpTests`: MockMvc로 정상·중복, 서명 누락/위조/잘못된 hex, 잘못된 timestamp·허용 경계/초과, 본문 변조, 서명 검증 후 파싱을 검증한다.
- 입력 오류 400, 미등록 거래 404와 중복 기록 롤백, 계약 충돌 409, 다른 보호 경로 인증 유지.
- `PaymentListenerTests`: 원본 이벤트를 트랜잭션에서 발행하고 실제 비동기 리스너·PG fake로 완료/미완료를 검증한다. 고정 Clock과 Awaitility를 쓴다.

```bash
test -f core/src/main/kotlin/io/github/ticketing/payment/adapter/provided/event/PaymentRequestListener.kt
test -f core/src/main/kotlin/io/github/ticketing/payment/adapter/provided/event/RefundRequestListener.kt
test -f core/src/main/kotlin/io/github/ticketing/payment/adapter/provided/web/PaymentWebhookController.kt
! rg -n 'import io\.github\.ticketing\.booking' core/src/main/kotlin/io/github/ticketing/payment
./scripts/agent/test/run-tests.sh
```

## 금지사항과 완료 기록

- 실패를 로그만 남기고 삼키지 않는다. 이유: 이벤트 재처리 대상이 사라진다.
- 본문 재직렬화·문자열 equals로 서명을 검증하지 않는다. 이유: 바이트 불일치와 타이밍 누출을 막는다.
- 테스트를 위해 모듈 의존·보안 정책을 완화하지 않는다.

AC 통과 후에만 `phases/3-payment/index.json`의 step 3 상태와 200자 이내 summary를 갱신한다. 실패는 error·error_message, 사용자 결정 필요는 blocked·blocked_reason이다. 로드맵 완료 처리와 직접 커밋은 하지 않는다.
