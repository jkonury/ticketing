package io.github.ticketing.payment.application.port.required

import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import java.time.Instant

interface PaymentCommandPort {
    fun save(payment: Payment): Payment
    fun findByOrderId(orderId: OrderId): Payment?
    fun findByIdempotencyKey(key: String): Payment?
    fun findByPgTransactionId(transactionId: String): Payment?
    fun recordWebhookEvent(eventId: String, type: String, transactionId: String, now: Instant): Boolean
}
