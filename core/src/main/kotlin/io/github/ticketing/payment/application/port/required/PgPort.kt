package io.github.ticketing.payment.application.port.required

import java.math.BigDecimal

/** PG 연동 출력 포트. 구현은 트랜잭션 밖에서만 호출한다. */
interface PgPort {
    fun requestPayment(request: PgPaymentRequest): PgRequestResult

    fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult
}

data class PgPaymentRequest(val orderId: String, val amount: BigDecimal, val idempotencyKey: String)

sealed interface PgRequestResult {
    data class Accepted(val transactionId: String) : PgRequestResult

    /** 4xx: 확정적 실패 */
    data class Rejected(val reason: String) : PgRequestResult

    /** 타임아웃·5xx·연결 실패: PG에서는 처리됐을 수 있어 결과가 확정되지 않았다 */
    data class Unknown(val cause: String) : PgRequestResult
}
