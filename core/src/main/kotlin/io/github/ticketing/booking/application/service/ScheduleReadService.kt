package io.github.ticketing.booking.application.service

import io.github.ticketing.booking.application.port.provided.ScheduleReadUseCase
import io.github.ticketing.booking.application.port.required.ScheduleReadPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class ScheduleReadService(
    private val reads: ScheduleReadPort,
    private val clock: Clock,
    @Value("\${ticketing.booking.expiry.payment-grace:30m}") private val paymentGrace: Duration,
) : ScheduleReadUseCase {
    @Transactional(readOnly = true)
    override fun availability(scheduleIds: List<Long>) = clock.instant().let { reads.availability(scheduleIds, it, it.minus(paymentGrace)) }

    @Transactional(readOnly = true)
    override fun sales(organizerId: Long) = reads.sales(organizerId)
}
