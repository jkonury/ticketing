package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.FlowSupport
import io.github.ticketing.payment.flow.PaymentFlowTests
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SeatHoldConcurrencyTests(
    mvc: MockMvc,
    jdbc: JdbcTemplate,
    redis: StringRedisTemplate,
    pg: PaymentFlowTests.TestPg,
    clock: PaymentFlowTests.MutableClock,
    collector: PaymentFlowTests.EventCollector,
) : FlowSupport(mvc, jdbc, redis, pg, clock, collector) {

    private fun concurrent(requests: List<() -> MvcResult>): List<MvcResult> {
        val ready = CountDownLatch(requests.size)
        val start = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = requests.map { request ->
                executor.submit<MvcResult> {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS))
                    request()
                }
            }

            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()

            return futures.map { it.get(30, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `같은 좌석을 동시에 선점하면 한 명만 성공하고 확정한다`() {
        val schedule = schedule()
        val users = (1..20).map { token() }

        val results = concurrent(users.map { user -> { book(user, schedule, schedule.seats.take(1)) } })

        assertThat(results.count { it.response.status == 201 }).isEqualTo(1)

        val failures = results.filter { it.response.status == 409 }

        assertThat(failures).hasSize(19)
        failures.forEach { assertThat(parse(it)["code"]).isEqualTo("SEAT_ALREADY_HELD") }
        assertThat(
            jdbc.queryForObject(
                "select count(*) from booking.booking where schedule_id = ? and status = 'PENDING'",
                Int::class.java,
                schedule.id,
            ),
        ).isEqualTo(1)

        val index = results.indexOfFirst { it.response.status == 201 }
        val id = created(results[index])

        pay(users[index], id)
        webhook(id, "PAYMENT_APPROVED")
        awaitStatus(users[index], id, "CONFIRMED")

        assertThat(sold(schedule)).isEqualTo(1)
    }

    @Test
    fun `겹치는 다중 좌석 요청은 전부 선점하거나 전부 실패한다`() {
        val schedule = schedule()
        val users = (1..3).map { token() }
        val selections = listOf(schedule.seats.subList(0, 2), schedule.seats.subList(1, 3), schedule.seats.subList(2, 4))

        val results = concurrent(users.indices.map { i -> { book(users[i], schedule, selections[i]) } })

        val successful = results.indices.filter { results[it].response.status == 201 }
        val failed = results.indices.filter { results[it].response.status == 409 }

        assertThat(successful.size + failed.size).isEqualTo(3)

        val owned = successful.flatMap { selections[it] }

        assertThat(owned).doesNotHaveDuplicates()
        failed.forEach { i ->
            assertThat(parse(results[i])["code"]).isEqualTo("SEAT_ALREADY_HELD")
            selections[i].filterNot { it in owned }.forEach { assertThat(redis.hasKey(hold(schedule, it))).isFalse() }
        }
        successful.forEach { i ->
            val id = (parse(results[i])["bookingId"] as Number).toLong()
            val owner = jdbc.queryForObject("select hold_token from booking.booking where id = ?", String::class.java, id)
            selections[i].forEach { assertThat(redis.opsForValue().get(hold(schedule, it))).isEqualTo(owner) }
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `Redis 선점을 우회해도 판매 좌석 유일 제약이 중복 확정을 막는다`(output: CapturedOutput) {
        val schedule = schedule()
        val first = token()
        val second = token()
        val seat = schedule.seats.first()
        val firstId = created(book(first, schedule, listOf(seat)))

        pay(first, firstId)
        webhook(firstId, "PAYMENT_APPROVED")
        awaitStatus(first, firstId, "CONFIRMED")
        eventually { assertThat(redis.opsForHash<String, String>().get(snapshot(schedule), seat.toString())).isEqualTo("SOLD") }

        // Redis 선점을 강제로 풀어 DB 제약만 남긴다
        redis.delete(hold(schedule, seat))
        redis.opsForHash<String, String>().put(snapshot(schedule), seat.toString(), "AVAILABLE")

        val secondId = created(book(second, schedule, listOf(seat)))

        pay(second, secondId)
        webhook(secondId, "PAYMENT_APPROVED")
        awaitStatus(second, secondId, "CANCELLED")

        assertThat(reason(secondId)).isEqualTo("SEAT_ALREADY_SOLD")
        eventually { assertThat(pg.refunds.filter { it.first == "tx-$secondId" }).hasSize(1) }
        assertThat(sold(schedule)).isEqualTo(1)
        assertThat(detail(first, firstId)["status"]).isEqualTo("CONFIRMED")
        assertThat(output.all).doesNotContain(" 500 ", "Internal Server Error")
    }
}
