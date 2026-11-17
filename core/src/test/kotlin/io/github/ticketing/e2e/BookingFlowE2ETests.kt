package io.github.ticketing.e2e

import io.github.ticketing.EventCollector
import io.github.ticketing.MutableClock
import io.github.ticketing.TestPg
import io.github.ticketing.e2e.support.FlowSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MockMvcTester

class BookingFlowE2ETests(
    mockMvcTester: MockMvcTester,
    jdbc: JdbcTemplate,
    redis: StringRedisTemplate,
    pg: TestPg,
    clock: MutableClock,
    collector: EventCollector,
) : FlowSupport(mockMvcTester, jdbc, redis, pg, clock, collector) {

    @Test
    fun `공연 등록부터 두 좌석 결제 확정까지 진행한다`() {
        val schedule = schedule()
        val user = token()
        val id = created(book(user, schedule, schedule.seats.take(2)))

        pay(user, id)

        assertThat(pg.requests.filter { it.orderId == id.toString() }).hasSize(1)

        webhook(id, "PAYMENT_APPROVED", 200000)
        awaitStatus(user, id, "CONFIRMED")

        val listResult = mockMvcTester.get().uri("/api/v1/bookings/me").header("Authorization", "Bearer $user").exchange()
        assertThat(listResult).hasStatus(HttpStatus.OK)
        val list = parse(listResult)

        assertThat((list["items"] as List<*>).map { (it as Map<*, *>)["bookingId"] }).contains(id.toInt())
        eventually {
            assertThat(sold(schedule)).isEqualTo(2)
            schedule.seats.take(2).forEach {
                assertThat(redis.opsForHash<String, String>().get(snapshot(schedule), it.toString())).isEqualTo("SOLD")
            }
        }
    }
}
