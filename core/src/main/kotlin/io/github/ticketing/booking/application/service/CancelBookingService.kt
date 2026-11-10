package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.BookingResult
import io.github.ticketing.booking.application.port.provided.CancelBookingUseCase
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.InvalidBookingStateException
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.NotBookingOwnerException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@Service
class CancelBookingService(
    private val bookings: BookingCommandPort,
    private val scheduleSeats: ScheduleSeatCommandPort,
    private val seatHold: SeatHoldPort,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : CancelBookingUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun cancel(memberId: Long, bookingId: Long): BookingResult = translatingRuleViolations {
        val member = MemberId(memberId)
        val saved = tx.execute {
            val booking = bookings.findById(BookingId(bookingId)) ?: throw BookingNotFoundException()
            if (booking.memberId != member) throw NotBookingOwnerException()
            when (booking.status) {
                BookingStatus.CANCELLED -> return@execute booking to false
                // 환불이 필요한 확정 예매 취소는 결제 연동과 함께 다룬다
                BookingStatus.CONFIRMED -> throw InvalidBookingStateException(booking.status)
                BookingStatus.PENDING, BookingStatus.EXPIRED -> Unit
            }
            booking.cancelByUser(member, clock.instant())
            bookings.save(booking) to true
        }
        val (booking, changed) = requireNotNull(saved)
        if (changed) {
            // 커밋 후에 선점을 푼다. 실패해도 TTL이 만료시킨다
            runCatching { seatHold.release(booking.scheduleId, booking.seats.map { it.seatId }, booking.holdToken, clock.instant()) }
                .onFailure { log.warn("예매 취소 후 선점 해제에 실패했다. bookingId={}", bookingId, it) }
            log.info("예매 취소 bookingId={} memberId={}", bookingId, memberId)
        }
        val labels = scheduleSeats.findSeats(booking.scheduleId, booking.seats.map { it.seatId }).associate { it.seatId to it.label }
        booking.toResult(labels)
    }
}
