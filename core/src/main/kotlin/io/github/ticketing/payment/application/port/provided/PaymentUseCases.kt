package io.github.ticketing.payment.application.port.provided

import java.math.BigDecimal
import java.time.Instant

interface RequestPaymentUseCase { fun request(command: RequestPaymentCommand) }
interface RequestRefundUseCase { fun request(command: RequestRefundCommand) }
interface HandlePaymentWebhookUseCase { fun handle(command: PaymentWebhookCommand) }

data class RequestPaymentCommand(val orderId: Long, val memberId: Long, val amount: BigDecimal, val idempotencyKey: String, val requestedAt: Instant)
data class RequestRefundCommand(val orderId: Long, val reason: String, val requestedAt: Instant)
enum class PaymentWebhookType { PAYMENT_APPROVED, PAYMENT_FAILED, REFUND_COMPLETED }
data class PaymentWebhookCommand(val eventId: String, val type: PaymentWebhookType, val transactionId: String, val orderId: Long, val amount: BigDecimal, val occurredAt: Instant, val failureReason: String?)
