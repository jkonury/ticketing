package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.BookingExpired
import io.github.ticketing.booking.application.port.provided.PaymentRequestResult
import io.github.ticketing.booking.application.port.provided.RequestPaymentUseCase
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingExpiredException
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.NotBookingOwnerException
import io.github.ticketing.booking.domain.requireSalesOpen
import io.github.ticketing.payment.PaymentRequested
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

@Service
class RequestPaymentService(
    private val bookings: BookingCommandPort,
    private val scheduleSeats: ScheduleSeatCommandPort,
    private val seatHold: SeatHoldPort,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : RequestPaymentUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    private sealed interface Outcome {
        data class Done(val booking: Booking) : Outcome
        data class Expired(val booking: Booking) : Outcome
    }

    override fun request(memberId: Long, bookingId: Long): PaymentRequestResult = translatingRuleViolations {
        val outcome = requireNotNull(tx.execute { requestInTransaction(MemberId(memberId), BookingId(bookingId)) })
        when (outcome) {
            is Outcome.Done -> PaymentRequestResult(bookingId, outcome.booking.status.name)

            is Outcome.Expired -> {
                // 커밋 후에 선점을 푼다. 실패해도 TTL이 만료시킨다
                val booking = outcome.booking
                runCatching { seatHold.release(booking.scheduleId, booking.seats.map { it.seatId }, booking.holdToken, clock.instant()) }
                    .onFailure { log.warn("만료 예매의 선점 해제에 실패했다. bookingId={}", bookingId, it) }
                log.info("결제 요청 시점에 만료되어 EXPIRED로 전환 bookingId={}", bookingId)
                throw BookingExpiredException()
            }
        }
    }

    private fun requestInTransaction(memberId: MemberId, bookingId: BookingId): Outcome {
        val booking = findOwned(memberId, bookingId)
        val now = clock.instant()
        if (booking.status == BookingStatus.EXPIRED) throw BookingExpiredException()
        val alreadyRequested = booking.paymentRequestedAt != null
        val expirable = booking.status == BookingStatus.PENDING && !alreadyRequested && booking.isExpired(now)
        if (booking.status == BookingStatus.PENDING && !alreadyRequested && !expirable) {
            val schedule = checkNotNull(scheduleSeats.findSchedule(booking.scheduleId)) { "회차 사본이 없다: ${booking.scheduleId.value}" }
            requireSalesOpen(schedule.startsAt, now)
        }
        return if (expirable) expire(booking, now) else requestPayment(booking, alreadyRequested, now)
    }

    private fun findOwned(memberId: MemberId, bookingId: BookingId): Booking {
        val booking = bookings.findById(bookingId) ?: throw BookingNotFoundException()
        if (booking.memberId != memberId) throw NotBookingOwnerException()
        return booking
    }

    private fun expire(booking: Booking, now: Instant): Outcome {
        booking.expire(now)
        val saved = bookings.save(booking)
        events.publishEvent(BookingExpired(requireNotNull(booking.id).value, booking.memberId.value, saved.scheduleId.value, now))
        events.requestCouponRelease(saved, "EXPIRED")
        return Outcome.Expired(saved)
    }

    private fun requestPayment(booking: Booking, alreadyRequested: Boolean, now: Instant): Outcome {
        // 이미 요청했으면 도메인이 아무것도 바꾸지 않는다. 그 경우 이벤트도 다시 발행하지 않는다
        booking.requestPayment(now)
        if (alreadyRequested) return Outcome.Done(booking)
        val saved = bookings.save(booking)
        val bookingId = requireNotNull(saved.id).value
        events.publishEvent(
            PaymentRequested(bookingId, saved.memberId.value, saved.payableAmount, paymentIdempotencyKey(bookingId), now),
        )
        log.info("결제 요청 bookingId={} memberId={}", bookingId, saved.memberId.value)
        return Outcome.Done(saved)
    }
}

/** 예매 하나에 결제 하나가 되도록 PG 멱등 키를 예매 ID로 고정한다. */
internal fun paymentIdempotencyKey(bookingId: Long) = "booking-$bookingId"
