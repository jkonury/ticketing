package io.github.ticketing.notification.service

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.booking.BookingExpired
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/** 실제 발송은 미뤘으므로 로그만 남긴다. 같은 이벤트가 다시 와도 부작용이 없다. */
@Component
class BookingNotificationListener {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun on(event: BookingConfirmed) {
        log.info("예매 확정 알림 bookingId={} memberId={}", event.bookingId, event.memberId)
    }

    @ApplicationModuleListener
    fun on(event: BookingCancelled) {
        log.info("예매 취소 알림 bookingId={} memberId={} reason={}", event.bookingId, event.memberId, event.reason)
    }

    @ApplicationModuleListener
    fun on(event: BookingExpired) {
        log.info("예매 만료 알림 bookingId={} memberId={}", event.bookingId, event.memberId)
    }
}
