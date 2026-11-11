package io.github.ticketing.booking.adapter.required.redis

import io.github.ticketing.booking.application.port.required.HoldResult
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.application.port.required.SeatSnapshotReconcileException
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Configuration(proxyBeanMethods = false)
class SeatHoldScriptConfig {
    @Bean
    fun seatHoldScript(): RedisScript<List<*>> = script("hold")

    @Bean
    fun seatReleaseScript(): RedisScript<List<*>> = script("release")

    @Bean
    fun seatMarkSoldScript(): RedisScript<Long> = DefaultRedisScript(load("mark-sold"), Long::class.java)

    @Bean
    fun seatMarkAvailableScript(): RedisScript<Long> = DefaultRedisScript(load("mark-available"), Long::class.java)

    @Bean
    fun seatReconcileScript(): RedisScript<Long> = DefaultRedisScript(load("reconcile-snapshot"), Long::class.java)

    private fun script(name: String) = DefaultRedisScript(load(name), List::class.java) as RedisScript<List<*>>

    private fun load(name: String): String =
        ClassPathResource("redis/booking/$name.lua").inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
}

/** 모든 키에 `{scheduleId}` 해시 태그를 넣어 Lua가 다루는 키를 한 슬롯에 모은다. */
@Component
class RedisSeatHoldAdapter(
    private val redis: StringRedisTemplate,
    @Qualifier("seatHoldScript") private val holdScript: RedisScript<List<*>>,
    @Qualifier("seatReleaseScript") private val releaseScript: RedisScript<List<*>>,
    @Qualifier("seatMarkSoldScript") private val markSoldScript: RedisScript<Long>,
    @Qualifier("seatMarkAvailableScript") private val markAvailableScript: RedisScript<Long>,
    @Qualifier("seatReconcileScript") private val reconcileScript: RedisScript<Long>,
) : SeatHoldPort {
    override fun hold(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, ttl: Duration, now: Instant): HoldResult {
        require(seatIds.isNotEmpty()) { "seatIds must not be empty" }
        val args = listOf(owner.value, ttl.toMillis().toString(), now.toEpochMilli().toString()) + ids(seatIds)
        val result = redis.execute(holdScript, keys(scheduleId, seatIds), *args.toTypedArray())
        return if (result.isNullOrEmpty()) {
            HoldResult.Held
        } else {
            HoldResult.Unavailable(result.map { SeatId(it.toString().toLong()) })
        }
    }

    override fun release(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant) {
        if (seatIds.isEmpty()) return
        val args = listOf(owner.value, now.toEpochMilli().toString()) + ids(seatIds)
        redis.execute(releaseScript, keys(scheduleId, seatIds), *args.toTypedArray())
    }

    override fun markSold(scheduleId: ScheduleId, seatIds: List<SeatId>, owner: HoldToken, now: Instant) {
        if (seatIds.isEmpty()) return
        val args = listOf(owner.value, now.toEpochMilli().toString()) + ids(seatIds)
        redis.execute(markSoldScript, keys(scheduleId, seatIds), *args.toTypedArray())
    }

    override fun markAvailable(scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant) {
        if (seatIds.isEmpty()) return
        val args = listOf(now.toEpochMilli().toString()) + ids(seatIds)
        redis.execute(markAvailableScript, listOf(snapshotKey(scheduleId), streamKey(scheduleId)), *args.toTypedArray())
    }

    override fun reconcileSnapshot(scheduleId: ScheduleId, seatIds: List<SeatId>, soldSeatIds: Set<SeatId>, now: Instant): Int {
        if (seatIds.isEmpty()) return 0
        val args = listOf(now.toEpochMilli().toString(), seatIds.size.toString()) + ids(seatIds) + ids(soldSeatIds.toList())
        return try {
            requireNotNull(redis.execute(reconcileScript, keys(scheduleId, seatIds), *args.toTypedArray())).toInt()
        } catch (exception: DataAccessException) {
            throw SeatSnapshotReconcileException(exception)
        }
    }

    override fun initSnapshot(scheduleId: ScheduleId, seatIds: List<SeatId>) {
        val key = snapshotKey(scheduleId)
        redis.executePipelined { connection ->
            val raw = redis.stringSerializer
            val keyBytes = raw.serialize(key)!!
            val available = raw.serialize("AVAILABLE")!!
            seatIds.forEach { connection.hashCommands().hSetNX(keyBytes, raw.serialize(it.value.toString())!!, available) }
            null
        }
    }

    private fun ids(seatIds: List<SeatId>) = seatIds.map { it.value.toString() }

    private fun keys(scheduleId: ScheduleId, seatIds: List<SeatId>): List<String> =
        listOf(snapshotKey(scheduleId), streamKey(scheduleId)) + seatIds.map { holdKey(scheduleId, it) }

    companion object {
        fun holdKey(scheduleId: ScheduleId, seatId: SeatId) = "booking:hold:{${scheduleId.value}}:${seatId.value}"

        fun snapshotKey(scheduleId: ScheduleId) = "booking:seats:{${scheduleId.value}}"

        fun streamKey(scheduleId: ScheduleId) = "booking:seat-events:{${scheduleId.value}}"
    }
}
