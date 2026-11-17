package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.BookingCouponUseCase
import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.BookingQueryPort
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.CouponChange
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.NotBookingOwnerException
import io.github.ticketing.booking.domain.applyCoupon
import io.github.ticketing.booking.domain.removeCoupon
import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.coupon.CouponReservationRequested
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

@Service
class BookingCouponService(
    private val bookings: BookingCommandPort,
    private val queries: BookingQueryPort,
    private val scheduleSeats: ScheduleSeatCommandPort,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) : BookingCouponUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun apply(memberId: Long, bookingId: Long, issuedCouponId: Long): BookingDetailView =
        change(memberId, bookingId, "REPLACED") { booking, now -> booking.applyCoupon(issuedCouponId, now) }

    override fun remove(memberId: Long, bookingId: Long): BookingDetailView =
        change(memberId, bookingId, "REMOVED") { booking, now -> booking.removeCoupon(now) }

    private fun change(
        memberId: Long,
        bookingId: Long,
        releaseReason: String,
        mutate: (Booking, Instant) -> CouponChange,
    ): BookingDetailView = translatingRuleViolations {
        tx.executeWithoutResult {
            val booking = bookings.findById(BookingId(bookingId)) ?: throw BookingNotFoundException()
            if (booking.memberId != MemberId(memberId)) throw NotBookingOwnerException()
            val now = clock.instant()
            val change = mutate(booking, now)
            if (change == CouponChange.NONE) return@executeWithoutResult
            val saved = bookings.save(booking)
            // 해제를 먼저 발행해 교체 시 이전 쿠폰이 새 예약보다 먼저 풀리게 한다
            change.releasedIssuedCouponId?.let { events.publishEvent(CouponReleaseRequested(bookingId, it, releaseReason)) }
            val applied = saved.appliedCoupon
            if (change.reservationRequested && applied != null) {
                val schedule = checkNotNull(scheduleSeats.findSchedule(saved.scheduleId)) { "회차 사본이 없다: ${saved.scheduleId.value}" }
                events.publishEvent(
                    CouponReservationRequested(bookingId, memberId, applied.issuedCouponId, schedule.performanceId, saved.listAmount, now),
                )
            }
            log.info("예매 쿠폰 변경 bookingId={} memberId={} change={}", bookingId, memberId, change)
        }
        queries.findDetail(bookingId) ?: throw BookingNotFoundException()
    }
}
