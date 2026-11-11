package io.github.ticketing.booking

import io.github.ticketing.TestcontainersConfiguration
import io.github.ticketing.catalog.ScheduleRegistered
import io.github.ticketing.catalog.ScheduleSeat
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import ticketing.jooq.booking.tables.references.SCHEDULE_SEAT
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
class BookingModuleTests(private val dsl: DSLContext) {
    private val ids = AtomicLong(System.currentTimeMillis())

    @Test
    fun `회차 등록 이벤트를 수신하면 좌석 사본을 저장한다`(scenario: Scenario) {
        val scheduleId = ids.incrementAndGet()
        val event = ScheduleRegistered(
            scheduleId,
            10,
            "공연",
            20,
            Instant.parse("2027-01-01T00:00:00Z"),
            listOf(ScheduleSeat(1, "VIP", "A1", BigDecimal("10000"))),
        )

        scenario.publish(event)
            .andWaitForStateChange(
                { dsl.fetchCount(SCHEDULE_SEAT, SCHEDULE_SEAT.SCHEDULE_ID.eq(scheduleId)) },
                { it == 1 },
            )
            .andVerify { count -> assertThat(count).isEqualTo(1) }
    }
}
