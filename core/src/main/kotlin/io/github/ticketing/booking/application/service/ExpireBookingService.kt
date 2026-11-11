package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.BookingExpired
import io.github.ticketing.booking.application.port.provided.ExpireBookingUseCase
import io.github.ticketing.booking.application.port.provided.ExpireResult
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.BookingId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

@Service
class ExpireBookingService(
    private val bookings: BookingCommandPort,
    private val seatHold: SeatHoldPort,
    private val events: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    @Value("\${ticketing.booking.expiry.payment-grace:30m}") private val paymentGrace: Duration,
) : ExpireBookingUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 청크의 다른 예매가 실패해도 각 예매의 커밋과 커밋 후 선점 해제를 독립적으로 보장한다. */
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun expireIfDue(bookingId: Long): ExpireResult {
        val now = clock.instant()
        val expired = transaction.execute {
            val booking = bookings.findById(BookingId(bookingId)) ?: return@execute null
            if (!booking.isSweepable(now, paymentGrace)) return@execute null
            booking.expireBySweep(now, paymentGrace)
            val saved = bookings.save(booking)
            events.publishEvent(BookingExpired(bookingId, saved.memberId.value, saved.scheduleId.value, now))
            saved
        } ?: return ExpireResult.Skipped("대상이 아니거나 예매가 없음")

        runCatching { seatHold.release(expired.scheduleId, expired.seats.map { it.seatId }, expired.holdToken, now) }
            .onFailure { log.warn("만료 예매의 선점 해제에 실패했다. bookingId={}", bookingId, it) }
        return ExpireResult.Expired
    }
}
