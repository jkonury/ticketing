package io.github.ticketing.payment.application.service

import io.github.ticketing.payment.application.port.provided.AdminPaymentView
import io.github.ticketing.payment.application.port.provided.PaymentQueryUseCase
import io.github.ticketing.payment.application.port.provided.PaymentStatusView
import io.github.ticketing.payment.application.port.required.PaymentQueryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentQueryService(private val payments: PaymentQueryPort) : PaymentQueryUseCase {
    @Transactional(readOnly = true)
    override fun findMine(memberId: Long, bookingIds: List<Long>): List<PaymentStatusView> = payments.findMine(memberId, bookingIds)

    @Transactional(readOnly = true)
    override fun findByBookingId(bookingId: Long): AdminPaymentView? = payments.findByBookingId(bookingId)
}
