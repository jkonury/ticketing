package io.github.ticketing.notification

import io.github.ticketing.TestcontainersConfiguration
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
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
    private val time = Instant.parse("2026-11-10T00:00:00Z")

    @Test
    fun `확정 이벤트를 수신해 알림 로그를 출력한다`(scenario: Scenario, output: CapturedOutput) {
        val event = BookingConfirmed(17001, 2, 3, listOf(4), BigDecimal.TEN, time)

        scenario.publish(event)
            .andWaitForStateChange({ output.out }, { it.contains("예매 확정 알림: bookingId=17001") })
            .andVerify { }
    }

    @Test
    fun `취소 이벤트를 수신해 알림 로그를 출력한다`(scenario: Scenario, output: CapturedOutput) {
        val event = BookingCancelled(17002, 2, 3, "PAYMENT_FAILED", false, time)

        scenario.publish(event)
            .andWaitForStateChange({ output.out }, { it.contains("예매 취소 알림: bookingId=17002") })
            .andVerify { }
    }
}
