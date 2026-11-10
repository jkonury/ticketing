package io.github.ticketing.payment.application.port.required

import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import java.time.Instant

interface PaymentCommandPort {
    fun save(payment: Payment): Payment

    fun findByOrderId(orderId: OrderId): Payment?

    fun findByPgTransactionId(transactionId: String): Payment?

    /** INSERT ... ON CONFLICT DO NOTHING. 처음 받은 eventId면 true */
    fun recordWebhookEvent(eventId: String, type: String, transactionId: String, now: Instant): Boolean
}
