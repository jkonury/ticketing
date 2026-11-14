package io.github.ticketing.booking.adapter.required.redis

import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal

@Component
internal class ScheduleSeatCache(private val redis: StringRedisTemplate, registry: MeterRegistry) {
    private val hits = registry.counter("ticketing.booking.seat.copy.cache", "result", "hit")
    private val misses = registry.counter("ticketing.booking.seat.copy.cache", "result", "miss")
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val writeScript = DefaultRedisScript(
        ClassPathResource("redis/booking/cache-seat-copies.lua").inputStream.use { it.reader().readText() },
        Long::class.java,
    )

    fun find(scheduleId: ScheduleId, seatIds: Collection<SeatId>): List<ScheduleSeatCopy> {
        val found = read(scheduleId, seatIds)
        hits.increment(found.size.toDouble())
        misses.increment((seatIds.size - found.size).toDouble())
        return found
    }

    private fun read(scheduleId: ScheduleId, seatIds: Collection<SeatId>): List<ScheduleSeatCopy> = try {
        val values = redis.opsForHash<String, String>().multiGet(key(scheduleId), seatIds.map { it.value.toString() })
        seatIds.zip(values).mapNotNull { (seatId, value) ->
            value?.let { decode(scheduleId, seatId, it) }
        }
    } catch (exception: DataAccessException) {
        logger.warn("좌석 사본 캐시 조회 실패, DB 조회로 전환: scheduleId={}", scheduleId.value, exception)
        emptyList()
    }

    fun putAfterCommit(scheduleId: ScheduleId, seats: List<ScheduleSeatCopy>) {
        if (seats.isEmpty()) return
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = put(scheduleId, seats)
            })
        } else {
            put(scheduleId, seats)
        }
    }

    private fun put(scheduleId: ScheduleId, seats: List<ScheduleSeatCopy>) {
        val arguments = listOf(TTL_SECONDS.toString()) + seats.flatMap {
            listOf(it.seatId.value.toString(), mapper.writeValueAsString(SeatValue(it.grade, it.label, it.price)))
        }
        try {
            redis.execute(writeScript, listOf(key(scheduleId)), *arguments.toTypedArray())
        } catch (exception: DataAccessException) {
            logger.warn("좌석 사본 캐시 저장 실패, DB 원장 유지: scheduleId={}", scheduleId.value, exception)
        }
    }

    private fun decode(scheduleId: ScheduleId, seatId: SeatId, value: String): ScheduleSeatCopy? = try {
        mapper.readValue(value, SeatValue::class.java)?.let { cached ->
            ScheduleSeatCopy(scheduleId, seatId, cached.grade, cached.label, cached.price)
        }
    } catch (exception: JacksonException) {
        logger.warn("좌석 사본 캐시 손상, DB 조회로 전환: scheduleId={}, seatId={}", scheduleId.value, seatId.value, exception)
        null
    }

    private data class SeatValue(val grade: String, val label: String, val price: BigDecimal)

    companion object {
        private const val TTL_SECONDS = 21600L

        fun key(scheduleId: ScheduleId) = "booking:seat-copies:v1:{${scheduleId.value}}"
    }
}
