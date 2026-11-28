package io.github.ticketing.payment.application.port.required

import io.github.ticketing.payment.application.port.provided.AdminPaymentView
import io.github.ticketing.payment.application.port.provided.PaymentStatusView

interface PaymentQueryPort {
    fun findMine(memberId: Long, bookingIds: List<Long>): List<PaymentStatusView>

    fun findByBookingId(bookingId: Long): AdminPaymentView?
}
