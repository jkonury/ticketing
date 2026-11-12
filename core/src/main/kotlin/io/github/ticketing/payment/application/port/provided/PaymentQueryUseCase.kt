package io.github.ticketing.payment.application.port.provided

import java.time.Instant

data class PaymentStatusView(
    val bookingId: Long,
    val status: String,
    val failureReason: String?,
    val approvedAt: Instant?,
    val refundedAt: Instant?,
)

interface PaymentQueryUseCase {
    fun findMine(memberId: Long, bookingIds: List<Long>): List<PaymentStatusView>
}
