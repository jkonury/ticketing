package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingForceCancelled
import io.github.ticketing.booking.application.port.provided.BookingResult
import io.github.ticketing.booking.application.port.provided.CancelBookingUseCase
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.CancelOutcome
import io.github.ticketing.booking.domain.CancelReason
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.NotBookingOwnerException
import io.github.ticketing.payment.RefundRequested
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class CancelBookingService(
    private val bookings: BookingCommandPort,
    private val scheduleSeats: ScheduleSeatCommandPort,
    private val seatHold: SeatHoldPort,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : CancelBookingUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun cancel(memberId: Long, bookingId: Long): BookingResult = translatingRuleViolations {
        val booking = tx.execute {
            val owned = findBooking(bookingId)
            if (owned.memberId != MemberId(memberId)) throw NotBookingOwnerException()
            if (owned.status == BookingStatus.CANCELLED) owned else cancel(owned, CancelReason.USER_REQUESTED, clock.instant())
        }
        result(requireNotNull(booking))
    }

    override fun forceCancel(actorId: Long, bookingId: Long, reason: String): BookingResult = translatingRuleViolations {
        val booking = tx.execute {
            val now = clock.instant()
            val saved = cancel(findBooking(bookingId), CancelReason.ADMIN_CANCELLED, now)
            events.publishEvent(BookingForceCancelled(UUID.randomUUID(), actorId, bookingId, reason, now))
            saved
        }
        result(requireNotNull(booking))
    }

    internal fun cancel(booking: Booking, reason: CancelReason, now: Instant): Booking {
        val bookingId = requireNotNull(booking.id)
        val outcome = booking.cancel(reason, now)
        val saved = bookings.save(booking)
        val refundRequired = outcome == CancelOutcome.RefundRequired

        if (refundRequired) {
            bookings.deleteSoldSeats(bookingId)
            events.publishEvent(RefundRequested(bookingId.value, reason.name, now))
        } else {
            events.requestCouponRelease(saved, if (reason == CancelReason.USER_REQUESTED) "USER_CANCELLED" else reason.name)
        }

        val schedule = checkNotNull(scheduleSeats.findSchedule(booking.scheduleId)) {
            "회차 사본이 없다: ${booking.scheduleId.value}"
        }
        events.publishEvent(
            BookingCancelled(
                bookingId.value,
                booking.memberId.value,
                booking.scheduleId.value,
                schedule.performanceId,
                schedule.organizerId,
                reason.name,
                refundRequired,
                now,
            ),
        )

        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = updateRedis(saved, outcome)
        })
        return saved
    }

    private fun findBooking(bookingId: Long): Booking = bookings.findById(BookingId(bookingId)) ?: throw BookingNotFoundException()

    private fun result(booking: Booking): BookingResult {
        val labels = scheduleSeats.findSeats(booking.scheduleId, booking.seats.map { it.seatId }).associate { it.seatId to it.label }
        return booking.toResult(labels)
    }

    // Redis 반영 실패 시에도 선점 TTL과 DB 제약이 중복 판매를 막는다.
    private fun updateRedis(booking: Booking, outcome: CancelOutcome) {
        val seatIds = booking.seats.map { it.seatId }
        runCatching {
            when (outcome) {
                CancelOutcome.ReleasedHold -> seatHold.release(booking.scheduleId, seatIds, booking.holdToken, clock.instant())
                CancelOutcome.RefundRequired -> seatHold.markAvailable(booking.scheduleId, seatIds, clock.instant())
            }
        }.onFailure { log.warn("예매 취소 후 Redis 좌석 상태 반영에 실패했다. bookingId={}", booking.id?.value, it) }

        log.info(
            "예매 취소 bookingId={} memberId={} confirmed={}",
            booking.id?.value,
            booking.memberId.value,
            outcome == CancelOutcome.RefundRequired,
        )
    }
}
