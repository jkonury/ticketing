package io.github.ticketing.settlement

import io.github.ticketing.TestcontainersConfiguration
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import ticketing.jooq.settlement.tables.references.BOOKING_REFUND
import ticketing.jooq.settlement.tables.references.BOOKING_SALE
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class, SettlementModuleTests.FixedClock::class)
class SettlementModuleTests(private val dsl: DSLContext, private val completed: CompletedEventPublications) {
    private val time = Instant.parse("2026-11-10T00:00:00Z")
    private val receivedAt = Instant.parse("2026-11-11T00:00:00Z")

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC)
    }

    @Test
    fun `확정 이벤트의 값을 매출 사본에 기록한다`(scenario: Scenario) {
        val event = BookingConfirmed(18001, 2, 3, 5, 6, listOf(4), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, time)

        scenario.publish(event)
            .andWaitForStateChange({
                dsl.selectFrom(BOOKING_SALE).where(BOOKING_SALE.BOOKING_ID.eq(event.bookingId)).fetch()
            }, { it.isNotEmpty() })
            .andVerify { sales ->
                val sale = sales.single()
                assertThat(sale.performanceId).isEqualTo(event.performanceId)
                assertThat(sale.organizerId).isEqualTo(event.organizerId)
                assertThat(sale.scheduleId).isEqualTo(event.scheduleId)
                assertThat(sale.amount).isEqualTo(event.amount.longValueExact())
                assertThat(sale.confirmedAt?.toInstant()).isEqualTo(time)
                assertThat(sale.receivedAt?.toInstant()).isEqualTo(receivedAt)
            }
    }

    @Test
    fun `확정 이벤트를 중복 수신해도 사본은 하나다`(scenario: Scenario) {
        val event = BookingConfirmed(18002, 2, 3, 5, 6, listOf(4), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, time)

        scenario.publish(event)
            .andWaitForStateChange { dsl.fetchCount(BOOKING_SALE, BOOKING_SALE.BOOKING_ID.eq(event.bookingId)) }
            .andVerify { assertThat(it).isEqualTo(1) }
        scenario.publish(event)
            .andWaitForStateChange({ completed.findAll().count { it.event == event } }, { it == 2 })
            .andVerify { assertThat(dsl.fetchCount(BOOKING_SALE, BOOKING_SALE.BOOKING_ID.eq(event.bookingId))).isEqualTo(1) }
    }

    @Test
    fun `환불이 필요한 취소는 확정 사본 없이도 기록한다`(scenario: Scenario) {
        val event = BookingCancelled(18003, 2, 3, 5, 6, "SEAT_ALREADY_SOLD", true, time)

        scenario.publish(event)
            .andWaitForStateChange({
                dsl.selectFrom(BOOKING_REFUND).where(BOOKING_REFUND.BOOKING_ID.eq(event.bookingId)).fetch()
            }, { it.isNotEmpty() })
            .andVerify { refunds ->
                val refund = refunds.single()
                assertThat(refund.performanceId).isEqualTo(event.performanceId)
                assertThat(refund.organizerId).isEqualTo(event.organizerId)
                assertThat(refund.reason).isEqualTo(event.reason)
                assertThat(refund.cancelledAt?.toInstant()).isEqualTo(time)
                assertThat(refund.receivedAt?.toInstant()).isEqualTo(receivedAt)
            }
    }

    @Test
    fun `환불이 필요 없는 취소는 기록하지 않는다`(scenario: Scenario) {
        val event = BookingCancelled(18004, 2, 3, 5, 6, "PAYMENT_FAILED", false, time)

        scenario.publish(event)
            .andWaitForStateChange({ completed.findAll().count { it.event == event } }, { it == 1 })
            .andVerify {
                assertThat(dsl.fetchCount(BOOKING_REFUND, BOOKING_REFUND.BOOKING_ID.eq(event.bookingId))).isZero()
            }
    }
}
