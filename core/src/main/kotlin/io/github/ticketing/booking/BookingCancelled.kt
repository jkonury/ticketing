package io.github.ticketing.booking

import java.time.Instant

/** [refundRequired]가 true이면 결제가 끝난 예매가 취소된 것이라 환불이 요청되었다. */
data class BookingCancelled(
    val bookingId: Long,
    val memberId: Long,
    val scheduleId: Long,
    val reason: String,
    val refundRequired: Boolean,
    val cancelledAt: Instant,
)
