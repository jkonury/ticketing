package io.github.ticketing.booking.application.port.provided

import io.github.ticketing.booking.domain.CouponState
import java.math.BigDecimal
import java.time.Instant

data class BookingSeatResult(val seatId: Long, val label: String, val price: BigDecimal)

data class BookingResult(
    val bookingId: Long,
    val status: String,
    val totalAmount: BigDecimal,
    val expiresAt: Instant,
    val seats: List<BookingSeatResult>,
)

/** [created]가 false이면 같은 멱등 키의 기존 예매를 그대로 돌려준 것이다. */
data class CreateBookingResult(val booking: BookingResult, val created: Boolean)

data class BookingSummaryView(
    val bookingId: Long,
    val performanceId: Long,
    val status: String,
    val performanceTitle: String,
    val startsAt: Instant,
    val seatCount: Int,
    val totalAmount: BigDecimal,
    val createdAt: Instant,
)

data class BookingPage(val items: List<BookingSummaryView>, val nextCursor: Long?)

data class BookingDetailView(
    val bookingId: Long,
    val memberId: Long,
    val scheduleId: Long,
    val status: String,
    val performanceTitle: String,
    val startsAt: Instant,
    val totalAmount: BigDecimal,
    val listAmount: BigDecimal,
    val discountAmount: BigDecimal,
    val payableAmount: BigDecimal,
    val coupon: BookingCouponView?,
    val expiresAt: Instant,
    val createdAt: Instant,
    val seats: List<BookingSeatResult>,
)

data class BookingCouponView(val issuedCouponId: Long, val state: CouponState, val discountAmount: BigDecimal?, val rejectReason: String?)
