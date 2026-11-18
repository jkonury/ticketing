package io.github.ticketing.payment

import java.time.Instant

data class PaymentRefunded(val paymentId: Long, val orderId: Long, val refundedAt: Instant)
