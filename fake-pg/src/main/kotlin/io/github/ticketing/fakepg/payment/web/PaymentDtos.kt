package io.github.ticketing.fakepg.payment.web

import io.github.ticketing.fakepg.payment.service.PaymentCommand
import io.github.ticketing.fakepg.payment.service.Scenario
import io.github.ticketing.fakepg.payment.service.Transaction
import io.github.ticketing.fakepg.payment.service.TransactionStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.Instant

data class PaymentRequest(
    @field:NotBlank val orderId: String,
    @field:Positive val amount: Long,
    val scenario: Scenario? = null,
) {
    fun toCommand() = PaymentCommand(orderId, amount, scenario)
}

data class PaymentAcceptedResponse(val transactionId: String, val status: TransactionStatus)

data class RefundAcceptedResponse(val transactionId: String, val status: TransactionStatus)

data class TransactionResponse(
    val transactionId: String,
    val orderId: String,
    val amount: Long,
    val status: TransactionStatus,
    val requestedAt: Instant,
    val approvedAt: Instant?,
    val refundedAt: Instant?,
) {
    companion object {
        fun from(tx: Transaction) = TransactionResponse(
            transactionId = tx.transactionId,
            orderId = tx.orderId,
            amount = tx.amount,
            status = tx.status,
            requestedAt = tx.requestedAt,
            approvedAt = tx.approvedAt,
            refundedAt = tx.refundedAt,
        )
    }
}
