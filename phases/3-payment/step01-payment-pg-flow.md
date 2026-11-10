# Step 1: payment-pg-flow

## 읽어야 할 파일

작업에 필요한 정보는 이 파일에 담겨 있다. 아래 파일은 작업 중 필요한 부분만 참조하라:

- step 0에서 만든 `core/src/main/kotlin/io/github/ticketing/payment/` 전체
- `core/src/main/kotlin/io/github/ticketing/member/security/SecurityConfig.kt` (웹훅 경로 공개 추가 대상)
- `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt` (테스트 프로퍼티 추가 대상)
- `core/src/main/resources/application.yml`, `.env.example`
- `docs/rules/backend.md` § 트랜잭션과 동시성, § 보안

## 작업

### 1. fake-pg 프로토콜 (1-fake-pg phase에서 구현됨, 그대로 따른다)

- 결제 `POST {baseUrl}/v1/payments`: 헤더 `Idempotency-Key`, 본문 `{orderId: string, amount: number, scenario?: string}` → 202 `{transactionId, status: "PENDING"}`. 같은 키로 다시 요청하면 같은 `transactionId`
- 환불 `POST {baseUrl}/v1/payments/{transactionId}/refund`: 헤더 `Idempotency-Key` → 202
- 웹훅(fake-pg → 코어): 본문 `{eventId, type: PAYMENT_APPROVED|PAYMENT_FAILED|REFUND_COMPLETED, transactionId, orderId, amount, occurredAt, failureReason}`, 헤더 `X-FakePg-Timestamp`(epoch 초), `X-FakePg-Signature` = hex(HMAC-SHA256(secret, `"{timestamp}.{rawBody}"`)). 중복 웹훅은 같은 `eventId`로 온다.
- `TIMEOUT` 시나리오에서는 응답이 늦어 클라이언트가 타임아웃되지만, 결제는 실제로 승인되고 웹훅이 온다.

### 2. PG 출력 포트와 어댑터

```kotlin
// payment.application.port.required
interface PgPort {
    fun requestPayment(request: PgPaymentRequest): PgRequestResult
    fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult
}
sealed interface PgRequestResult {
    data class Accepted(val transactionId: String) : PgRequestResult
    data class Rejected(val reason: String) : PgRequestResult     // 4xx: 확정적 실패
    data class Unknown(val cause: String) : PgRequestResult       // 타임아웃·5xx·연결 실패: 결과 미확정
}
```

- `payment.adapter.required.pg.FakePgAdapter`: `RestClient`를 쓰고 연결·읽기 타임아웃을 설정한다.
- 설정 `ticketing.payment.fake-pg.base-url`(기본 `http://localhost:8090`), `connect-timeout`, `read-timeout`(기본 3s), `webhook-secret`(`${FAKE_PG_WEBHOOK_SECRET}`), 웹훅 시각 허용 오차(기본 5분)
- `.env.example`의 `FAKE_PG_WEBHOOK_SECRET`를 코어도 쓴다는 주석을 확인하고, 없으면 추가한다.

### 3. 결제 요청 처리 (`adapter.provided.event` → 유스케이스)

- `PaymentRequested` 리스너:
  1. 트랜잭션 A에서 `orderId`로 결제를 찾는다. 없으면 `REQUESTED`로 저장한다(멱등).
  2. **트랜잭션 밖에서** `PgPort.requestPayment`를 호출한다. 멱등 키는 이벤트의 `idempotencyKey`다.
  3. 결과를 반영한다. `Accepted`면 트랜잭션 B에서 `attachPgTransaction`. `Rejected`면 `FAILED`로 바꾸고 `PaymentFailed`를 발행한다. `Unknown`이면 상태를 `REQUESTED`로 두고 warn 로그만 남긴다. 이유: PG 쪽에서는 결제가 진행됐을 수 있어 실패로 확정하면 안 된다. 웹훅이나 M2 결제 대사가 결과를 맞춘다.
- 이미 `REQUESTED`가 아닌 결제에 같은 이벤트가 다시 오면 PG를 호출하지 않는다.
- 웹훅이 `attachPgTransaction`보다 먼저 올 수 있다. 그래서 웹훅의 결제 조회는 `transactionId`로 찾고, 없으면 `orderId`로 찾는다. 이때 `transactionId`도 함께 붙인다.

### 4. 웹훅 `POST /api/v1/payments/webhooks/fake-pg` (`adapter.provided.web`)

- `SecurityConfig`에 이 경로의 `permitAll`을 추가한다. 인증은 서명이 대신한다.
- **원문 바이트**로 서명을 검증한다(파싱 후 재직렬화하면 바이트가 달라진다). 비교는 상수 시간(`MessageDigest.isEqual`)으로 하고, 시각이 허용 오차를 벗어나도 거부한다. 서명이 없거나 틀리면 401 `INVALID_WEBHOOK_SIGNATURE`
- 하나의 트랜잭션에서 처리한다. `recordWebhookEvent`가 false면(중복) 바로 200을 반환한다. 아니면 타입에 따라 `approve` → `PaymentSucceeded`, `fail` → `PaymentFailed`, `completeRefund` → `PaymentRefunded`를 발행한다. `AlreadyApplied`면 이벤트를 다시 발행하지 않는다.
- 처리할 수 없는 웹훅(알 수 없는 거래)은 404로 응답해 PG가 재시도하게 한다. 로그에 `paymentId`와 `orderId`를 포함하고, 서명 값과 본문 전체는 로그에 남기지 않는다.

### 5. 환불 (`RefundRequested` 리스너)

- 트랜잭션에서 `requestRefund`를 실행하고, 트랜잭션 밖에서 `PgPort.requestRefund`를 호출한다. 멱등 키는 `"refund-{orderId}"`다.
- `REFUND_REQUESTED`나 `REFUNDED` 상태에 같은 이벤트가 다시 오면 PG를 다시 호출하지 않는다.
- `Unknown`이면 예외를 던져 이벤트 발행 레지스트리의 재처리 대상으로 남긴다. 환불은 PG 멱등 키 덕분에 재시도해도 안전하다.

### 6. 테스트 (`core/src/test/kotlin/io/github/ticketing/payment/`)

- `IntegrationTestSupport`의 `properties`에 테스트용 웹훅 secret을 추가한다.
- 통합 테스트에서는 `PgPort`를 테스트용 fake 빈(`@TestConfiguration` + `@Primary`)으로 바꾼다. fake는 호출을 기록하고, 결과를 테스트에서 지정할 수 있다. 웹훅은 테스트가 직접 서명을 계산해 **실제 HTTP 엔드포인트**(MockMvc)로 보낸다.
  - 결제 요청 → `Accepted` → 승인 웹훅이 오면 `PaymentSucceeded` 1건. 같은 웹훅이 3번 와도 1건이다.
  - `Rejected`면 `PaymentFailed`, `Unknown`이면 `REQUESTED` 유지. 이후 승인 웹훅이 오면 성공으로 처리된다.
  - 같은 `PaymentRequested`가 두 번 와도 PG 호출은 1번이다.
  - 서명 오류, 시각 초과는 401
  - `RefundRequested` → PG 환불 호출 1번 → 환불 웹훅이 오면 `PaymentRefunded`
  - **booking과의 연결**: 예매 생성 → 결제 요청 API → fake PG → 승인 웹훅이 오면 예매가 `CONFIRMED`가 된다(Awaitility).
- `FakePgAdapter`는 `MockRestServiceServer`로 검증한다. 요청 헤더·본문, 202 → Accepted, 4xx → Rejected, 5xx → Unknown을 확인하고, 타임아웃 → Unknown은 가능한 범위에서 확인한다.

### 7. 문서

- `docs/design.md` § 10 외부 연동에 fake-pg 프로토콜 요약(경로, 웹훅 서명, 시나리오, Unknown 처리)을 적는다.
- `docs/roadmap.md` M1-5의 네 항목("PG 출력 포트와 가짜 PG 어댑터", "멱등 키 기반 결제 요청과 웹훅 중복 처리", "결제 결과 이벤트 발행", "환불, 만료 후 결제 성공 시 자동 환불")을 `[x]`로 바꾼다.

## Acceptance Criteria

```bash
test -d core/src/main/kotlin/io/github/ticketing/payment/adapter/required/pg
test -d core/src/main/kotlin/io/github/ticketing/payment/adapter/provided/web
./scripts/agent/test/run-tests.sh :core:test --tests "io.github.ticketing.payment.*" --tests "io.github.ticketing.ModularityTests"
rg -q '^- \[x\] 멱등 키 기반 결제 요청과 웹훅 중복 처리' docs/roadmap.md
rg -q '^- \[x\] 환불, 만료 후 결제 성공 시 자동 환불' docs/roadmap.md
```

## 검증 절차

1. 위 AC를 실행하고, 실패하면 원인을 수정한다.
2. `phases/3-payment/index.json`의 해당 step을 업데이트한다:
   - 성공 → `"status": "completed"`, `"summary": "생성/수정한 파일 경로와 핵심 결론을 2줄·200자 이내로"`
   - 해결할 수 없는 실패 → `"status": "error"`, `"error_message": "구체적 에러 내용"`
   - 사용자 개입 필요 → `"status": "blocked"`, `"blocked_reason": "구체적 사유"` 후 즉시 중단

## 금지사항

- `@Transactional` 메서드 안에서 `PgPort`를 호출하지 마라. 이유: 외부 호출이 DB 커넥션과 락을 붙든다(backend 규칙).
- PG 타임아웃이나 5xx를 결제 실패(`PaymentFailed`)로 처리하지 마라. 이유: 실제로는 승인됐을 수 있어, 좌석은 반환했는데 돈은 빠져나가는 상황이 생긴다.
- `io.github.ticketing.booking`의 타입을 main 코드에서 import하지 마라. 이유: ADR 0017. 테스트 코드에서 흐름을 확인하는 것은 허용한다.
- 서명 검증을 `String.equals`로 하거나 파싱한 객체를 재직렬화해 검증하지 마라. 이유: 타이밍 공격, 바이트 불일치
- 웹훅 secret을 `application.yml`에 기본값으로 넣지 마라. 이유: 비밀 값은 저장소에 넣지 않는다.
- 검색은 `rg`/`fd`를 우선 쓴다. 이유: `.gitignore`를 존중해 `build/` 결과가 섞이지 않고 출력이 짧다. 사용 가능한 도구는 Bash, Read, Write, Edit뿐이다.
- 기존 테스트를 깨뜨리지 마라.
- git commit 하지 마라. 이유: execute.py가 AC 통과를 확인한 뒤 step 단위로 커밋한다.
