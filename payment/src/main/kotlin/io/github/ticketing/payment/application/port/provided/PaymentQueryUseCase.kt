package io.github.ticketing.payment.application.port.provided

import java.math.BigDecimal
import java.time.Instant

data class PaymentStatusView(
    val bookingId: Long,
    val status: String,
    val failureReason: String?,
    val approvedAt: Instant?,
    val refundedAt: Instant?,
)

data class AdminPaymentView(
    val bookingId: Long,
    val status: String,
    val amount: BigDecimal,
    val failureReason: String?,
    val requestedAt: Instant,
    val approvedAt: Instant?,
    val refundRequestedAt: Instant?,
    val refundedAt: Instant?,
)

interface PaymentQueryUseCase {
    fun findMine(memberId: Long, bookingIds: List<Long>): List<PaymentStatusView>

    fun findByBookingId(bookingId: Long): AdminPaymentView?
}
