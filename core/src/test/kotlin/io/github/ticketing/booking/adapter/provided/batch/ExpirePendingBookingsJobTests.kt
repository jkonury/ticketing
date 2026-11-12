package io.github.ticketing.booking.adapter.provided.batch

import io.github.ticketing.FailingExpireBookingUseCase
import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.booking.BookingExpired
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.holdKey
import io.github.ticketing.booking.application.port.provided.ExpireBookingUseCase
import io.github.ticketing.booking.application.port.provided.ExpireResult
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.application.service.ExpireBookingService
import io.github.ticketing.booking.domain.BookedSeat
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean

@RecordApplicationEvents
class ExpirePendingBookingsJobTests(
    private val launcher: JobLauncher,
    private val expirePendingBookingsJob: Job,
    private val bookings: BookingCommandPort,
    private val seatHold: SeatHoldPort,
    private val redis: StringRedisTemplate,
    private val transaction: TransactionTemplate,
    private val failingUseCase: FailingExpireBookingUseCase,
    private val clock: MutableClock,
) : IntegrationTestSupport() {
    @BeforeEach
    fun fixClock() {
        clock.now = NOW
    }

    @Test
    fun `낙관적 락 충돌은 다른 예매를 되돌리지 않고 다음 실행에서 처리한다`() {
        val conflict = create(11, NOW.minusSeconds(400))
        val other = create(12, NOW.minusSeconds(400))
        failingUseCase.failingId = requireNotNull(conflict.id).value
        failingUseCase.failNext.set(true)

        assertThat(launcher.run(expirePendingBookingsJob, parameters(11)).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(status(conflict)).isEqualTo(BookingStatus.PENDING)
        assertThat(status(other)).isEqualTo(BookingStatus.EXPIRED)
        assertThat(launcher.run(expirePendingBookingsJob, parameters(12)).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(status(conflict)).isEqualTo(BookingStatus.EXPIRED)
    }

    @Test
    fun `만료 대상만 처리하고 재실행해도 이벤트를 다시 발행하지 않는다`(events: ApplicationEvents) {
        val expired = create(1, NOW.minusSeconds(301))
        val graceExpired = create(2, NOW.minusSeconds(2100), requested = true)
        val withinGrace = create(3, NOW.minusSeconds(2099), requested = true)
        val future = create(4, NOW.minusSeconds(299))
        val confirmed = create(5, NOW.minusSeconds(400), confirmed = true)
        val token = expired.holdToken
        seatHold.hold(expired.scheduleId, expired.seats.map { it.seatId }, token, Duration.ofHours(1), NOW)

        assertThat(launcher.run(expirePendingBookingsJob, parameters(1)).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(launcher.run(expirePendingBookingsJob, parameters(2)).status).isEqualTo(BatchStatus.COMPLETED)

        assertThat(status(expired)).isEqualTo(BookingStatus.EXPIRED)
        assertThat(status(graceExpired)).isEqualTo(BookingStatus.EXPIRED)
        assertThat(status(withinGrace)).isEqualTo(BookingStatus.PENDING)
        assertThat(status(future)).isEqualTo(BookingStatus.PENDING)
        assertThat(status(confirmed)).isEqualTo(BookingStatus.CONFIRMED)
        assertThat(redis.hasKey(holdKey(expired.scheduleId, expired.seats.single().seatId))).isFalse()
        val ids = listOf(requireNotNull(expired.id).value, requireNotNull(graceExpired.id).value)
        assertThat(events.stream(BookingExpired::class.java).filter { it.bookingId in ids }.count()).isEqualTo(2)
    }

    private fun parameters(run: Long) = JobParametersBuilder().addLong("run.id", run).toJobParameters()

    private fun status(booking: Booking): BookingStatus = requireNotNull(
        transaction.execute { bookings.findById(requireNotNull(booking.id))?.status },
    )

    private fun create(seat: Long, created: Instant, requested: Boolean = false, confirmed: Boolean = false): Booking {
        val booking = Booking.create(
            MemberId(90000 + seat),
            ScheduleId(90000 + seat),
            listOf(BookedSeat(SeatId(seat), BigDecimal.TEN)),
            HoldToken("test-$seat"),
            created,
            "batch-$seat",
        )
        if (requested) booking.requestPayment(created)
        if (confirmed) booking.confirm(created)
        return requireNotNull(transaction.execute { bookings.save(booking) })
    }

    companion object {
        private val NOW = Instant.parse("2026-11-11T12:00:00Z")
    }
}
