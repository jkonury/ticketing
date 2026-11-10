package io.github.ticketing.payment.application.port.provided

import java.math.BigDecimal

/** 서명 검증을 통과한 PG 웹훅을 반영한다. 같은 eventId는 한 번만 반영한다. */
interface HandlePgWebhookUseCase {
    fun handle(command: PgWebhookCommand): PgWebhookOutcome
}

enum class PgWebhookType { PAYMENT_APPROVED, PAYMENT_FAILED, REFUND_COMPLETED }

data class PgWebhookCommand(
    val eventId: String,
    val type: PgWebhookType,
    val transactionId: String,
    /** PG가 돌려준 주문 식별자. 우리가 보낸 값이 아니면 null이다 */
    val orderId: Long?,
    val amount: BigDecimal,
    val failureReason: String?,
)

enum class PgWebhookOutcome { APPLIED, DUPLICATE }
