package io.github.ticketing.booking.adapter.required.redis

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.holdKey
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.snapshotKey
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.streamKey
import io.github.ticketing.booking.application.port.required.HoldResult
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RedisSeatHoldAdapterTests : IntegrationTestSupport() {
    @Autowired lateinit var port: SeatHoldPort

    @Autowired lateinit var redis: StringRedisTemplate

    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val ttl = Duration.ofMinutes(5)
    private val schedule = ScheduleId(System.nanoTime())
    private val alice = HoldToken("alice")
    private val bob = HoldToken("bob")

    private fun seats(vararg ids: Long) = ids.map { SeatId(it) }

    @AfterEach
    fun cleanup() {
        redis.keys("booking:*{${schedule.value}}*")?.let { if (it.isNotEmpty()) redis.delete(it) }
    }

    @Test
    fun `선점하면 TTL이 걸리고 스냅샷과 Stream에 기록된다`() {
        val result = port.hold(schedule, seats(1, 2), alice, ttl, now)

        assertThat(result).isEqualTo(HoldResult.Held)
        assertThat(redis.getExpire(holdKey(schedule, SeatId(1)), TimeUnit.MILLISECONDS)).isBetween(1L, ttl.toMillis())
        assertThat(redis.opsForValue().get(holdKey(schedule, SeatId(2)))).isEqualTo("alice")

        val expiresAt = now.toEpochMilli() + ttl.toMillis()

        assertThat(redis.opsForHash<String, String>().get(snapshotKey(schedule), "1")).isEqualTo("HELD:$expiresAt")

        val records = redis.opsForStream<String, String>().range(streamKey(schedule), org.springframework.data.domain.Range.unbounded())!!

        assertThat(records).hasSize(1)
        assertThat(records[0].value).containsEntry("seatIds", "1,2").containsEntry("status", "HELD")
            .containsEntry("holdExpiresAt", expiresAt.toString()).containsEntry("at", now.toEpochMilli().toString())
    }

    @Test
    fun `이미 선점된 좌석이 있으면 아무것도 선점하지 않고 해당 좌석을 돌려준다`() {
        port.hold(schedule, seats(2), alice, ttl, now)

        val result = port.hold(schedule, seats(1, 2, 3), bob, ttl, now)

        assertThat(result).isEqualTo(HoldResult.Unavailable(seats(2)))
        assertThat(redis.hasKey(holdKey(schedule, SeatId(1)))).isFalse()
        assertThat(redis.hasKey(holdKey(schedule, SeatId(3)))).isFalse()
    }

    @Test
    fun `다른 owner의 해제는 무시하고 소유자 해제는 AVAILABLE로 돌린다`() {
        port.hold(schedule, seats(1), alice, ttl, now)

        port.release(schedule, seats(1), bob, now)
        assertThat(redis.hasKey(holdKey(schedule, SeatId(1)))).isTrue()

        port.release(schedule, seats(1), alice, now)
        assertThat(redis.hasKey(holdKey(schedule, SeatId(1)))).isFalse()
        assertThat(redis.opsForHash<String, String>().get(snapshotKey(schedule), "1")).isEqualTo("AVAILABLE")
        assertThat(port.hold(schedule, seats(1), bob, ttl, now)).isEqualTo(HoldResult.Held)
    }

    @Test
    fun `판매 후에는 선점 키가 사라져도 재선점할 수 없고 판매 해제 후에는 가능하다`() {
        port.hold(schedule, seats(1), alice, ttl, now)
        port.markSold(schedule, seats(1), alice, now)

        assertThat(redis.hasKey(holdKey(schedule, SeatId(1)))).isFalse()
        assertThat(redis.opsForHash<String, String>().get(snapshotKey(schedule), "1")).isEqualTo("SOLD")
        assertThat(port.hold(schedule, seats(1), bob, ttl, now)).isEqualTo(HoldResult.Unavailable(seats(1)))

        port.markAvailable(schedule, seats(1), now)

        assertThat(redis.opsForHash<String, String>().get(snapshotKey(schedule), "1")).isEqualTo("AVAILABLE")
        assertThat(port.hold(schedule, seats(1), bob, ttl, now)).isEqualTo(HoldResult.Held)
    }

    @Test
    fun `선점 키 TTL이 끝나면 스냅샷이 HELD여도 다시 선점할 수 있다`() {
        port.hold(schedule, seats(1), alice, ttl, now)
        redis.delete(holdKey(schedule, SeatId(1)))

        assertThat(port.hold(schedule, seats(1), bob, ttl, now)).isEqualTo(HoldResult.Held)
    }

    @Test
    fun `initSnapshot은 이미 있는 필드를 덮어쓰지 않는다`() {
        port.hold(schedule, seats(1), alice, ttl, now)

        port.initSnapshot(schedule, seats(1, 2))

        val hash = redis.opsForHash<String, String>()
        assertThat(hash.get(snapshotKey(schedule), "1")).startsWith("HELD:")
        assertThat(hash.get(snapshotKey(schedule), "2")).isEqualTo("AVAILABLE")
    }

    @Test
    fun `스레드 50개가 같은 좌석을 선점하면 정확히 1건만 성공한다`() {
        val success = AtomicInteger()
        runConcurrently(50) { i ->
            if (port.hold(schedule, seats(1), HoldToken("u$i"), ttl, now) == HoldResult.Held) success.incrementAndGet()
        }
        assertThat(success.get()).isEqualTo(1)
    }

    @Test
    fun `좌석이 겹치는 다중 선점은 한쪽만 성공하고 실패한 쪽은 아무것도 선점하지 못한다`() {
        val results = java.util.concurrent.ConcurrentHashMap<String, HoldResult>()

        runConcurrently(2) { i ->
            val requestedSeats = if (i == 0) seats(1, 2) else seats(2, 3)
            results["u$i"] = port.hold(schedule, requestedSeats, HoldToken("u$i"), ttl, now)
        }

        assertThat(results.values.count { it == HoldResult.Held }).isEqualTo(1)

        val winner = results.entries.first { it.value == HoldResult.Held }.key
        val owners = (1L..3L).mapNotNull { redis.opsForValue().get(holdKey(schedule, SeatId(it))) }

        assertThat(owners).hasSize(2).containsOnly(winner)
    }

    private fun runConcurrently(n: Int, task: (Int) -> Unit) {
        val start = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = (0 until n).map { i -> executor.submit { start.await(); task(i) } }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        }
    }
}
