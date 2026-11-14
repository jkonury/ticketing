package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.adapter.required.redis.ScheduleSeatCache
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

internal class ScheduleSeatCacheIntegrationTests(
    private val port: ScheduleSeatCommandPort,
    private val cache: ScheduleSeatCache,
    private val redis: StringRedisTemplate,
    private val tx: TransactionTemplate,
) : IntegrationTestSupport() {
    @Test
    fun `등록은 커밋 뒤 캐시를 채우고 hit은 DB를 조회하지 않는다`() {
        val seats = seats()
        val key = ScheduleSeatCache.key(seats.first().scheduleId)
        tx.execute {
            save(seats)
            assertThat(redis.hasKey(key)).isFalse()
        }
        val database = mock(DSLContext::class.java)
        val repository = mock(ScheduleSeatCopyJpaRepository::class.java)
        val adapter = ScheduleSeatPersistenceAdapter(repository, database, cache)

        val found = adapter.findSeats(seats.first().scheduleId, listOf(SeatId(2)))

        assertThat(found).containsExactly(seats[1])
        assertThat(redis.getExpire(key)).isBetween(1L, Duration.ofHours(6).seconds)
        verifyNoInteractions(database, repository)
    }

    @Test
    fun `부분 누락과 손상은 DB로 보완하고 없는 좌석은 캐시하지 않는다`() {
        val seats = seats()
        tx.execute { save(seats) }
        val scheduleId = seats.first().scheduleId
        val key = ScheduleSeatCache.key(scheduleId)
        redis.opsForHash<String, String>().delete(key, "2")
        redis.opsForHash<String, String>().put(key, "3", "broken-json")

        val found = tx.execute { port.findSeats(scheduleId, listOf(SeatId(1), SeatId(2), SeatId(3), SeatId(99))) }

        assertThat(found).containsExactlyInAnyOrderElementsOf(seats)
        assertThat(cache.find(scheduleId, listOf(SeatId(1), SeatId(2), SeatId(3)))).containsExactlyElementsOf(seats)
        assertThat(redis.opsForHash<String, String>().hasKey(key, "99")).isFalse()
    }

    @Test
    fun `JSON null도 캐시 miss로 처리한다`() {
        val seats = seats()
        tx.execute { save(seats) }
        val scheduleId = seats.first().scheduleId
        redis.opsForHash<String, String>().put(ScheduleSeatCache.key(scheduleId), "1", "null")

        val found = port.findSeats(scheduleId, listOf(SeatId(1)))

        assertThat(found).containsExactly(seats.first())
    }

    @Test
    fun `만료된 캐시는 DB에서 복원한다`() {
        val seats = seats()
        tx.execute { save(seats) }
        val scheduleId = seats.first().scheduleId
        val key = ScheduleSeatCache.key(scheduleId)
        redis.expire(key, Duration.ZERO)
        assertThat(redis.hasKey(key)).isFalse()

        val found = tx.execute { port.findSeats(scheduleId, listOf(SeatId(1))) }

        assertThat(found).containsExactly(seats.first())
        assertThat(cache.find(scheduleId, listOf(SeatId(1)))).containsExactly(seats.first())
    }

    @Test
    fun `등록 롤백은 DB와 캐시에 사본을 남기지 않는다`() {
        val seats = seats()
        val scheduleId = seats.first().scheduleId

        tx.execute {
            save(seats)
            it.setRollbackOnly()
        }

        assertThat(redis.hasKey(ScheduleSeatCache.key(scheduleId))).isFalse()
        assertThat(tx.execute { port.findSeats(scheduleId, listOf(SeatId(1))) }).isEmpty()
        assertThat(redis.hasKey(ScheduleSeatCache.key(scheduleId))).isFalse()
    }

    @Test
    fun `조회 롤백은 캐시를 채우지 않고 중복 등록은 기존 값을 보존한다`() {
        val seats = seats()
        tx.execute { save(seats) }
        val scheduleId = seats.first().scheduleId
        val key = ScheduleSeatCache.key(scheduleId)
        tx.execute { save(seats.map { it.copy(price = BigDecimal.ONE) }) }
        assertThat(cache.find(scheduleId, listOf(SeatId(1)))).containsExactly(seats.first())
        redis.delete(key)

        tx.execute {
            assertThat(port.findSeats(scheduleId, listOf(SeatId(1)))).containsExactly(seats.first())
            it.setRollbackOnly()
        }

        assertThat(redis.hasKey(key)).isFalse()
    }

    @Test
    fun `Redis 키 타입 오류에도 DB 조회 결과를 반환한다`() {
        val seats = seats()
        tx.execute { save(seats) }
        val scheduleId = seats.first().scheduleId
        val key = ScheduleSeatCache.key(scheduleId)
        redis.delete(key)
        redis.opsForValue().set(key, "wrong-type", Duration.ofMinutes(1))

        val found = tx.execute { port.findSeats(scheduleId, listOf(SeatId(1))) }

        assertThat(found).containsExactly(seats.first())
        redis.delete(key)
    }

    private fun seats(): List<ScheduleSeatCopy> {
        val scheduleId = ScheduleId(System.nanoTime())
        return (1L..3L).map { ScheduleSeatCopy(scheduleId, SeatId(it), "VIP", "A구역 $it", BigDecimal(150000)) }
    }

    private fun save(seats: List<ScheduleSeatCopy>) {
        val now = Instant.parse("2026-11-10T10:00:00Z")
        port.saveIfAbsent(ScheduleCopy(seats.first().scheduleId, 1, "공연", 2, now, now), seats)
    }
}
