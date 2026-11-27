# 언제 mock하는가

기본은 [백엔드 테스트 지침의 테스트 종류](../../../../docs/rules/backend-testing.md#테스트-종류)다. 유스케이스는 출력 포트를 **fake 구현**으로 대체하고 mock 남용을 피하며, DB·Redis·Kafka는 Testcontainers로 검증한다(H2·embedded Redis 금지). 이 문서는 그 위에서 판단하는 기준이다.

**시스템 경계에서만** mock(또는 fake)한다.

- 외부 API(PG, 이메일 등). 이 저장소에서는 `fake-pg`나 포트의 가짜 구현
- 시간·무작위성: `Clock`을 고정한다. `Thread.sleep`으로 기다리지 않는다
- DB: mock하지 않고 Testcontainers의 실제 DB를 쓴다
- 파일 시스템(경우에 따라)

mock하지 않는 것:

- 우리가 만든 클래스·모듈
- 내부 협력자
- 우리가 통제하는 모든 것

## mock하기 쉽게 설계하기

시스템 경계에서는 mock하기 쉬운 인터페이스를 설계한다.

**1. 의존성을 주입한다**

외부 의존성은 내부에서 만들지 않고 밖에서 넘긴다. 이 저장소에서는 생성자 주입이다.

```kotlin
// 쉽다
class PaymentProcessor(private val paymentClient: PaymentClient) {
    fun pay(order: Order) = paymentClient.charge(order.total)
}

// 어렵다
class PaymentProcessor {
    fun pay(order: Order): PaymentResult {
        val client = PgClient(System.getenv("PG_KEY"))
        return client.charge(order.total)
    }
}
```

**2. 범용 fetcher보다 SDK 스타일 인터페이스를 선호한다**

조건 분기가 든 함수 하나 대신, 외부 연산마다 구체적인 함수를 만든다.

```kotlin
// GOOD: 각 함수를 독립적으로 대체할 수 있다
interface PaymentGateway {
    fun charge(request: ChargeRequest): ChargeResult
    fun refund(request: RefundRequest): RefundResult
}

// BAD: 대체하려면 대체물 안에 조건 로직이 필요하다
interface PaymentGateway {
    fun call(endpoint: String, body: Map<String, Any>): Map<String, Any>
}
```

SDK 방식이면:

- 각 대체물이 하나의 구체적인 형태만 반환한다
- 테스트 준비에 조건 로직이 없다
- 테스트가 어떤 엔드포인트를 쓰는지 한눈에 보인다
- 엔드포인트별로 타입 안전하다
