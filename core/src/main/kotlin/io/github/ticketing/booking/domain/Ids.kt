package io.github.ticketing.booking.domain

@JvmInline
value class BookingId(val value: Long)

@JvmInline
value class ScheduleId(val value: Long)

@JvmInline
value class SeatId(val value: Long)

@JvmInline
value class MemberId(val value: Long)

/** 선점 소유자 식별자(UUID 문자열). */
@JvmInline
value class HoldToken(val value: String)
