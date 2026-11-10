package io.github.ticketing.booking.application.port.required

import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import java.time.Duration
import java.time.Instant

interface SeatHoldPort {
    /** 전부 선점하거나 전부 실패한다. */
    fun hold(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, ttl: Duration, now: Instant): HoldResult

    /** 소유자일 때만 해제한다. */
    fun release(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant)

    /** DB 확정 커밋 후 호출한다. */
    fun markSold(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant)

    /** 확정 취소 후 호출한다. */
    fun markAvailable(scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant)

    /** 이미 있는 필드는 덮어쓰지 않는다(HSETNX). */
    fun initSnapshot(scheduleId: ScheduleId, seatIds: List<SeatId>)
}

sealed interface HoldResult {
    data object Held : HoldResult

    data class Unavailable(val seatIds: List<SeatId>) : HoldResult
}
