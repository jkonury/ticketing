package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.HandleCouponResultUseCase
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.CouponState
import io.github.ticketing.booking.domain.InvalidCouponDiscountException
import io.github.ticketing.booking.domain.onCouponRejected
import io.github.ticketing.booking.domain.onCouponReserved
import io.github.ticketing.coupon.CouponReleaseRequested
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal

/**
 * 반영하지 못한 Reserved(교체·해제·종료 뒤 늦은 응답, 무효 할인)는 쿠폰 선점이 남지 않도록 즉시 해제를 요청한다.
 * 낙관적 락 충돌은 삼키지 않고 던져 이벤트 발행 레지스트리가 다시 시도하게 한다.
 */
@Service
class CouponResultService(
    private val bookings: BookingCommandPort,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
) : HandleCouponResultUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onReserved(bookingId: Long, issuedCouponId: Long, discountAmount: BigDecimal) {
        tx.executeWithoutResult {
            val booking = bookings.findById(BookingId(bookingId))
            if (booking == null) {
                log.warn("쿠폰 예약 결과의 예매를 찾을 수 없어 해제를 요청한다 bookingId={} issuedCouponId={}", bookingId, issuedCouponId)
                release(bookingId, issuedCouponId, "STALE_RESERVATION")
                return@executeWithoutResult
            }
            val current = booking.appliedCoupon
            if (current != null && current.issuedCouponId == issuedCouponId && current.state == CouponState.RESERVED) {
                log.info("이미 반영한 쿠폰 예약 결과라 무시한다 bookingId={} issuedCouponId={}", bookingId, issuedCouponId)
                return@executeWithoutResult
            }
            try {
                if (booking.onCouponReserved(issuedCouponId, discountAmount)) {
                    bookings.save(booking)
                } else {
                    log.info("늦은 쿠폰 예약 결과라 해제를 요청한다 bookingId={} issuedCouponId={}", bookingId, issuedCouponId)
                    release(bookingId, issuedCouponId, "STALE_RESERVATION")
                }
            } catch (e: InvalidCouponDiscountException) {
                log.warn("무효한 할인액이라 쿠폰을 거절 처리하고 해제를 요청한다 bookingId={} discount={}", bookingId, discountAmount, e)
                booking.onCouponRejected(issuedCouponId, "INVALID_DISCOUNT")
                bookings.save(booking)
                release(bookingId, issuedCouponId, "INVALID_DISCOUNT")
            }
        }
    }

    override fun onRejected(bookingId: Long, issuedCouponId: Long, reason: String) {
        tx.executeWithoutResult {
            val booking = bookings.findById(BookingId(bookingId))
            if (booking == null) {
                log.warn("쿠폰 거절 결과의 예매를 찾을 수 없다 bookingId={} issuedCouponId={}", bookingId, issuedCouponId)
                return@executeWithoutResult
            }
            if (booking.onCouponRejected(issuedCouponId, reason)) {
                bookings.save(booking)
                log.info("쿠폰이 거절되어 쿠폰 없이 진행한다 bookingId={} issuedCouponId={} reason={}", bookingId, issuedCouponId, reason)
            }
        }
    }

    private fun release(bookingId: Long, issuedCouponId: Long, reason: String) {
        events.publishEvent(CouponReleaseRequested(bookingId, issuedCouponId, reason))
    }
}
