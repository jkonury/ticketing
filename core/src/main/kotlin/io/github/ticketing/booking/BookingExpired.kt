package io.github.ticketing.booking

import java.time.Instant

data class BookingExpired(val bookingId: Long, val memberId: Long, val scheduleId: Long, val expiredAt: Instant)
