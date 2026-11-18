package io.github.ticketing.booking

import java.time.Instant

/** 예매가 취소되었다. notification과 settlement가 구독한다. [refundRequired]가 true이면 환불이 요청되었다. */
data class BookingCancelled(
    val bookingId: Long,
    val memberId: Long,
    val scheduleId: Long,
    val performanceId: Long,
    val organizerId: Long,
    val reason: String,
    val refundRequired: Boolean,
    val cancelledAt: Instant,
)
