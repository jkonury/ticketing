package io.github.ticketing.payment

import java.time.Instant

/** [orderId]의 결제를 환불해 달라는 요청. 같은 주문에 여러 번 와도 환불은 한 번만 일어나야 한다. */
data class RefundRequested(val orderId: Long, val reason: String, val requestedAt: Instant)
