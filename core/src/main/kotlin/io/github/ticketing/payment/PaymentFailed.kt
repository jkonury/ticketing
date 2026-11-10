package io.github.ticketing.payment

import java.time.Instant

data class PaymentFailed(val paymentId: Long, val orderId: Long, val reason: String, val failedAt: Instant)
