package io.github.ticketing.notification.service

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class BookingNotificationListener {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun on(event: BookingConfirmed) {
        log.info("예매 확정 알림: bookingId={}, memberId={}, scheduleId={}", event.bookingId, event.memberId, event.scheduleId)
    }

    @ApplicationModuleListener
    fun on(event: BookingCancelled) {
        log.info(
            "예매 취소 알림: bookingId={}, memberId={}, scheduleId={}, reason={}",
            event.bookingId,
            event.memberId,
            event.scheduleId,
            event.reason,
        )
    }
}
