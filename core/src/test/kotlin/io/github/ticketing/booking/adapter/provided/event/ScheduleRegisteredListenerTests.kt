package io.github.ticketing.booking.adapter.provided.event

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.catalog.ScheduleRegistered
import io.github.ticketing.catalog.ScheduleSeat
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class ScheduleRegisteredListenerTests(
    private val publisher: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val jdbc: JdbcTemplate,
) : IntegrationTestSupport() {

    private fun seatCount(scheduleId: Long) =
        jdbc.queryForObject("select count(*) from booking.schedule_seat where schedule_id = ?", Long::class.java, scheduleId)!!

    private fun scheduleCount(scheduleId: Long) =
        jdbc.queryForObject("select count(*) from booking.schedule where schedule_id = ?", Long::class.java, scheduleId)!!

    @Test
    fun `ScheduleRegistered를 받으면 사본이 생기고 같은 이벤트를 다시 받아도 행 수가 같다`() {
        val scheduleId = System.nanoTime()
        val event = ScheduleRegistered(
            scheduleId,
            10,
            "공연",
            20,
            Instant.parse("2026-12-01T10:00:00Z"),
            listOf(ScheduleSeat(1, "VIP", "A1", BigDecimal(150000)), ScheduleSeat(2, "R", "B1", BigDecimal(100000))),
        )

        tx.executeWithoutResult { publisher.publishEvent(event) }

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(scheduleCount(scheduleId)).isEqualTo(1)
            assertThat(seatCount(scheduleId)).isEqualTo(2)
        }

        // 두 번째 이벤트가 처리될 시간을 준 뒤에도 행 수가 변하지 않는지 확인한다
        tx.executeWithoutResult { publisher.publishEvent(event) }
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted {
            assertThat(scheduleCount(scheduleId)).isEqualTo(1)
            assertThat(seatCount(scheduleId)).isEqualTo(2)
        }
    }
}
