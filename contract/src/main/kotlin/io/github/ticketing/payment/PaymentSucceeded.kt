package io.github.ticketing.payment

import java.math.BigDecimal
import java.time.Instant

data class PaymentSucceeded(val paymentId: Long, val orderId: Long, val amount: BigDecimal, val paidAt: Instant)
