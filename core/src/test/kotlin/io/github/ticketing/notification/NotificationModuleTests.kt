package io.github.ticketing.notification

import io.github.ticketing.TestcontainersConfiguration
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.booking.BookingExpired
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import java.math.BigDecimal
import java.time.Instant

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class NotificationModuleTests {
    private val at = Instant.parse("2026-11-10T00:00:00Z")

    @Test
    fun `예매 확정 이벤트를 받으면 알림 로그를 남긴다`(scenario: Scenario, output: CapturedOutput) {
        scenario.publish(BookingConfirmed(101, 7, 3, listOf(1, 2), BigDecimal(30_000), at))
            .andWaitForStateChange({ output.all }, { it.contains("예매 확정 알림 bookingId=101 memberId=7") })
    }

    @Test
    fun `예매 취소 이벤트를 받으면 알림 로그를 남긴다`(scenario: Scenario, output: CapturedOutput) {
        scenario.publish(BookingCancelled(102, 7, 3, "PAYMENT_FAILED", false, at))
            .andWaitForStateChange({ output.all }, { it.contains("예매 취소 알림 bookingId=102 memberId=7 reason=PAYMENT_FAILED") })
    }

    @Test
    fun `예매 만료 이벤트를 받으면 알림 로그를 남긴다`(scenario: Scenario, output: CapturedOutput) {
        scenario.publish(BookingExpired(103, 7, 3, at))
            .andWaitForStateChange({ output.all }, { it.contains("예매 만료 알림 bookingId=103 memberId=7") })
    }

    @Test
    fun `같은 이벤트가 다시 와도 예외 없이 처리된다`(scenario: Scenario, output: CapturedOutput) {
        val event = BookingConfirmed(104, 7, 3, listOf(1), BigDecimal(10_000), at)
        scenario.publish(event).andWaitForStateChange({ output.all }, { it.contains("bookingId=104") })
        scenario.publish(event).andWaitForStateChange({ output.all }, { Regex("예매 확정 알림 bookingId=104").findAll(it).count() == 2 })
        assertThat(output.all).doesNotContain("Exception")
    }
}
