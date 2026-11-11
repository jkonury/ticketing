package io.github.ticketing.notification.service

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.math.BigDecimal
import java.time.Instant

@ExtendWith(OutputCaptureExtension::class)
class BookingNotificationListenerTests {
    private val listener = BookingNotificationListener()
    private val time = Instant.parse("2026-11-10T00:00:00Z")

    @Test
    fun `확정 이벤트를 받으면 예매 식별자를 로그에 남긴다`(output: CapturedOutput) {
        listener.on(BookingConfirmed(1, 2, 3, listOf(4), BigDecimal.TEN, time))

        assertThat(output.out).contains("예매 확정 알림: bookingId=1, memberId=2, scheduleId=3")
    }

    @Test
    fun `취소 이벤트를 받으면 예매 식별자와 취소 사유를 로그에 남긴다`(output: CapturedOutput) {
        listener.on(BookingCancelled(1, 2, 3, "PAYMENT_FAILED", false, time))

        assertThat(output.out).contains("예매 취소 알림: bookingId=1, memberId=2, scheduleId=3, reason=PAYMENT_FAILED")
    }
}
