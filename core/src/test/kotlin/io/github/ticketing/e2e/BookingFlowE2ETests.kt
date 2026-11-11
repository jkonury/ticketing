package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.FlowSupport
import io.github.ticketing.payment.flow.PaymentFlowTests
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class BookingFlowE2ETests(
    mvc: MockMvc,
    jdbc: JdbcTemplate,
    redis: StringRedisTemplate,
    pg: PaymentFlowTests.TestPg,
    clock: PaymentFlowTests.MutableClock,
    collector: PaymentFlowTests.EventCollector,
) : FlowSupport(mvc, jdbc, redis, pg, clock, collector) {

    @Test
    fun `공연 등록부터 두 좌석 결제 확정까지 진행한다`() {
        val schedule = schedule()
        val user = token()
        val id = created(book(user, schedule, schedule.seats.take(2)))

        pay(user, id)

        assertThat(pg.requests.filter { it.orderId == id.toString() }).hasSize(1)

        webhook(id, "PAYMENT_APPROVED", 200000)
        awaitStatus(user, id, "CONFIRMED")

        val list =
            parse(mvc.perform(get("/api/v1/bookings/me").header("Authorization", "Bearer $user")).andExpect(status().isOk).andReturn())

        assertThat((list["items"] as List<*>).map { (it as Map<*, *>)["bookingId"] }).contains(id.toInt())
        eventually {
            assertThat(sold(schedule)).isEqualTo(2)
            schedule.seats.take(2).forEach {
                assertThat(redis.opsForHash<String, String>().get(snapshot(schedule), it.toString())).isEqualTo("SOLD")
            }
        }
    }
}
