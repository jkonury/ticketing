package io.github.ticketing.payment.application.port.required

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

interface PgTransactionQueryPort {
    fun findTransactions(date: LocalDate): List<PgTransaction>
}

enum class PgTransactionStatus { PENDING, APPROVED, FAILED, REFUNDED }

data class PgTransaction(
    val transactionId: String,
    val orderId: Long,
    val amount: BigDecimal,
    val status: PgTransactionStatus,
    val requestedAt: Instant,
    val approvedAt: Instant?,
    val refundedAt: Instant?,
)
