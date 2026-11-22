package io.github.ticketing.payment.application.port.required

import java.math.BigDecimal

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
