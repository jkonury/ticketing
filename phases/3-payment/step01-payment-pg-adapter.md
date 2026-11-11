# Step 1: payment-pg-adapter

## 목표와 참조

PG 출력 포트, fake-pg HTTP 어댑터와 설정을 만든다. 상태 변경·입력 어댑터는 만들지 않는다. 코딩·테스트 규칙은 `docs/rules/backend.md`를 따른다.

- 이전 step의 `core/src/main/kotlin/io/github/ticketing/payment/domain/Payment.kt`: 금액 계약만 확인한다.
- `fake-pg/src/main/kotlin/io/github/ticketing/fakepg/payment/web/PaymentController.kt`, `PaymentDtos.kt`: HTTP 계약.
- `core/build.gradle.kts`, `gradle/libs.versions.toml`, `core/src/main/resources/application.yml`, `.env.example`.
- `core/src/test/kotlin/io/github/ticketing/IntegrationTestSupport.kt`.

## 포트와 HTTP 계약

`payment.application.port.required`:

```kotlin
data class PgPaymentRequest(val orderId: String, val amount: BigDecimal, val idempotencyKey: String)
interface PgPort {
    fun requestPayment(request: PgPaymentRequest): PgRequestResult
    fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult
}
sealed interface PgRequestResult {
    data class Accepted(val transactionId: String) : PgRequestResult
    data class Rejected(val reason: String) : PgRequestResult
    data class Unknown(val cause: String) : PgRequestResult
}
```

`payment.adapter.required.pg.FakePgAdapter`는 RestClient를 사용한다. fake-pg의 DTO나 서비스를 import하지 않고 내부 wire DTO를 둔다.

| 요청 | 계약 |
|---|---|
| 결제 | POST /v1/payments, Idempotency-Key, JSON `{orderId: string, amount: integer}` → 202 `{transactionId, status: "PENDING"}` |
| 환불 | POST /v1/payments/{transactionId}/refund, Idempotency-Key, 본문 없음 → 202 `{transactionId, status}` |

- 금액은 longValueExact로 손실 없이 변환한다. 시나리오는 PG 설정으로 제어하고 사용자 API·이벤트 필드를 추가하지 않는다.
- 유효한 202는 Accepted, 4xx는 Rejected, 타임아웃·연결 오류·5xx·예상하지 않은 상태/응답은 Unknown이다. 환불 응답의 거래 ID가 요청과 달라도 Unknown이다.
- 어댑터 안에서 자동 재시도하지 않는다. 응답 본문·예외 메시지 전체를 로그나 결과에 복사하지 않고 안정적인 오류 분류만 사용한다.

## 설정

`payment.adapter.required.pg.PaymentPgConfiguration`과 secret을 toString에 노출하지 않는 프로퍼티 타입을 만든다. 접두사는 `ticketing.payment.fake-pg`다.

| 키 | 기본값 |
|---|---|
| base-url | http://localhost:8090 |
| connect-timeout, read-timeout | 각각 3s |
| webhook-secret | `${FAKE_PG_WEBHOOK_SECRET}`; 기본 비밀 값 없음 |
| webhook-timestamp-tolerance | 5m |

실제 HTTP 클라이언트에 연결·읽기 타임아웃을 적용한다. IntegrationTestSupport와 별도로 컨텍스트를 구성하는 테스트에도 필요한 테스트용 프로퍼티를 제공한다. `.env.example`에는 같은 secret을 코어와 PG가 공유한다는 설명만 추가한다.

[Boot 4.1.2 공식 문서](https://docs.spring.io/spring-boot/reference/io/rest-client.html)로 HTTP 클라이언트 의존성과 설정 API를 확인한다. 필요한 의존성만 추가하고 기존 BOM/version catalog를 따른다.

## 테스트와 AC

- `FakePgAdapterTests`: MockRestServiceServer로 요청·헤더·본문·상태 분류를 검증한다.
- 실제 로컬 HTTP 서버의 지연/연결 종료로 타임아웃·Unknown 분류를 검증한다. 외부 PG에 의존하지 않고 서버와 executor를 종료한다.
- 새 secret 설정으로 기존 테스트 컨텍스트가 깨지지 않는지 확인한다.

```bash
test -f core/src/main/kotlin/io/github/ticketing/payment/adapter/required/pg/FakePgAdapter.kt
test -f core/src/main/kotlin/io/github/ticketing/payment/application/port/required/PgPort.kt
./scripts/agent/test/run-tests.sh
```

## 금지사항과 완료 기록

- 어댑터에서 결제 이벤트를 발행하지 않는다. 이유: HTTP 분류와 상태 전이는 다른 책임이다.
- secret 기본값·raw PG 응답 로그·입력 리스너를 추가하지 않는다.

AC 통과 후에만 `phases/3-payment/index.json`의 step 1 상태와 200자 이내 summary를 갱신한다. 실패는 error·error_message, 사용자 결정 필요는 blocked·blocked_reason이다. 로드맵 완료 처리와 직접 커밋은 하지 않는다.
