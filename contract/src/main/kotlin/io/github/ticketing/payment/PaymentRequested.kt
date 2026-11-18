package io.github.ticketing.payment

import java.math.BigDecimal
import java.time.Instant

/** 결제를 요청한다. [orderId]는 요청자가 정한 주문 식별자이며 payment는 그 의미를 모른다. */
data class PaymentRequested(
    val orderId: Long,
    val memberId: Long,
    val amount: BigDecimal,
    val idempotencyKey: String,
    val requestedAt: Instant,
)
