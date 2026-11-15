package io.github.ticketing.booking.adapter.required.redis

import io.github.ticketing.booking.application.port.required.WarmupFixturePort
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
internal class RedisWarmupFixtureAdapter(private val redis: StringRedisTemplate, private val cache: ScheduleSeatCache) :
    WarmupFixturePort {
    // 트랜잭션 밖에서 호출하므로 putAfterCommit이 곧바로 쓴다
    override fun seedSeatCopies(scheduleId: ScheduleId, seats: List<ScheduleSeatCopy>) {
        cache.putAfterCommit(scheduleId, seats)
    }

    override fun cleanup(scheduleId: ScheduleId, seatIds: List<SeatId>) {
        val keys = listOf(
            ScheduleSeatCache.key(scheduleId),
            RedisSeatHoldAdapter.snapshotKey(scheduleId),
            RedisSeatHoldAdapter.streamKey(scheduleId),
        ) + seatIds.map { RedisSeatHoldAdapter.holdKey(scheduleId, it) }
        redis.delete(keys)
    }
}
