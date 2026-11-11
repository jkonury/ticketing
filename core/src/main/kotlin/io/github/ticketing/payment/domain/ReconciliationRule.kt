package io.github.ticketing.payment.domain

import java.math.BigDecimal

enum class MismatchType { MISSING_INTERNAL, MISSING_PG, STATUS_MISMATCH, AMOUNT_MISMATCH, TRANSACTION_ID_MISMATCH }

enum class ReconciliationPgStatus { PENDING, APPROVED, FAILED, REFUNDED }

data class InternalPaymentRecord(val id: Long, val status: PaymentStatus, val amount: BigDecimal, val transactionId: String?)
data class PgTransactionRecord(val transactionId: String, val status: ReconciliationPgStatus, val amount: BigDecimal)

object ReconciliationRule {
    fun compare(internal: InternalPaymentRecord?, pg: PgTransactionRecord?): List<MismatchType> {
        if (internal == null || pg == null) {
            return when {
                internal == null && pg == null -> emptyList()
                internal == null -> listOf(MismatchType.MISSING_INTERNAL)
                else -> listOf(MismatchType.MISSING_PG)
            }
        }
        return comparePresent(internal, pg)
    }

    private fun comparePresent(internal: InternalPaymentRecord, pg: PgTransactionRecord): List<MismatchType> {
        val expected = when (internal.status) {
            PaymentStatus.REQUESTED -> ReconciliationPgStatus.PENDING
            PaymentStatus.SUCCEEDED, PaymentStatus.REFUND_REQUESTED -> ReconciliationPgStatus.APPROVED
            PaymentStatus.REFUNDED -> ReconciliationPgStatus.REFUNDED
            PaymentStatus.FAILED -> ReconciliationPgStatus.FAILED
        }
        return buildList {
            if (pg.status != expected) add(MismatchType.STATUS_MISMATCH)
            if (internal.amount.compareTo(pg.amount) != 0) add(MismatchType.AMOUNT_MISMATCH)
            if (!internal.transactionId.isNullOrBlank() &&
                internal.transactionId != pg.transactionId
            ) {
                add(MismatchType.TRANSACTION_ID_MISMATCH)
            }
        }
    }
}
