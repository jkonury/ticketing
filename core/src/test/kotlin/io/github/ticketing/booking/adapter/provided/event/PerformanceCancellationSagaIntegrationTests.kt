package io.github.ticketing.booking.adapter.provided.event

import io.github.ticketing.EventCollector
import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.domain.BookedSeat
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.CancelReason
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import io.github.ticketing.booking.domain.applyCoupon
import io.github.ticketing.catalog.PerformanceCancelled
import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.payment.RefundRequested
import io.github.ticketing.publicationCompleted
import io.github.ticketing.publicationIds
import io.github.ticketing.support.KafkaPaymentStub
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import ticketing.jooq.booking.tables.references.SCHEDULE
import ticketing.jooq.booking.tables.references.SOLD_SEAT
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import ticketing.jooq.booking.tables.references.BOOKING_ as BOOKING

@TestPropertySource(properties = ["ticketing.booking.performance-cancellation.chunk-size=2"])
class PerformanceCancellationSagaIntegrationTests(
    private val bookings: BookingCommandPort,
    private val schedules: RegisterScheduleSeatsUseCase,
    private val dsl: DSLContext,
    private val jdbc: JdbcTemplate,
    private val events: ApplicationEventPublisher,
    private val tx: TransactionTemplate,
    private val publications: IncompleteEventPublications,
    private val collector: EventCollector,
    private val payments: KafkaPaymentStub,
) : IntegrationTestSupport() {
    private val performanceId = uniqueId()

    @Test
    fun `공연의 여러 회차에서 미결제와 확정 예매만 취소한다`() {
        val first = schedule()
        val second = schedule()
        val other = schedule(uniqueId())
        val pendingWithCoupon = booking(first, 1, coupon = true)
        val confirmed = booking(first, 2, BookingStatus.CONFIRMED)
        val pending = booking(second, 1)
        val expired = booking(second, 2, BookingStatus.EXPIRED)
        val alreadyCancelled = booking(second, 3, BookingStatus.CANCELLED)
        val unrelated = booking(other, 1)
        val publication = cancelPerformance()

        await().atMost(Duration.ofSeconds(10)).until { publicationCompleted(jdbc, publication) }

        for (id in listOf(pendingWithCoupon, confirmed, pending)) {
            assertThat(status(id)).isEqualTo("CANCELLED")
            val reason = dsl.select(BOOKING.CANCEL_REASON).from(BOOKING).where(BOOKING.ID.eq(id)).fetchSingle().value1()
            assertThat(reason).isEqualTo("PERFORMANCE_CANCELLED")
            val cancelled = collector.of(BookingCancelled::class.java) { it.bookingId == id }.single()
            assertThat(cancelled.reason).isEqualTo("PERFORMANCE_CANCELLED")
            assertThat(cancelled.refundRequired).isEqualTo(id == confirmed)
        }
        assertThat(status(expired)).isEqualTo("EXPIRED")
        assertThat(status(alreadyCancelled)).isEqualTo("CANCELLED")
        assertThat(status(unrelated)).isEqualTo("PENDING")
        assertThat(dsl.select(BOOKING.CANCEL_REASON).from(BOOKING).where(BOOKING.ID.eq(alreadyCancelled)).fetchSingle().value1())
            .isEqualTo("USER_REQUESTED")
        assertThat(dsl.fetchExists(SOLD_SEAT, SOLD_SEAT.BOOKING_ID.eq(confirmed))).isFalse()
        assertThat(payments.awaitRefundRequested(confirmed).reason).isEqualTo("PERFORMANCE_CANCELLED")
        val couponRelease = collector.of(CouponReleaseRequested::class.java) { it.orderId == pendingWithCoupon }.single()
        assertThat(couponRelease.reason).isEqualTo("PERFORMANCE_CANCELLED")
        assertThat(collector.of(RefundRequested::class.java) { it.orderId == pending || it.orderId == pendingWithCoupon }).isEmpty()
    }

    @Test
    fun `두 번째 청크가 실패하면 그 청크만 롤백되고 미완료 이벤트 재발행으로 복구한다`() {
        val scheduleId = schedule()
        val first = booking(scheduleId, 1, BookingStatus.CONFIRMED)
        val second = booking(scheduleId, 2)
        val third = booking(scheduleId, 3)
        val failing = booking(scheduleId, 4, BookingStatus.CONFIRMED)
        dsl.execute("alter table booking.booking add constraint saga_test_failure check (id <> $failing or status <> 'CANCELLED')")
        val publication = try {
            val id = cancelPerformance()
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                assertThat(jdbc.queryForObject("select status from public.event_publication where id = ?", String::class.java, id))
                    .isEqualTo("FAILED")
            }
            assertThat(status(first)).isEqualTo("CANCELLED")
            assertThat(status(second)).isEqualTo("CANCELLED")
            assertThat(status(third)).isEqualTo("PENDING")
            assertThat(status(failing)).isEqualTo("CONFIRMED")
            assertThat(dsl.select(SCHEDULE.CANCELLED).from(SCHEDULE).where(SCHEDULE.SCHEDULE_ID.eq(scheduleId)).fetchSingle().value1())
                .isTrue()
            id
        } finally {
            dsl.execute("alter table booking.booking drop constraint saga_test_failure")
        }

        publications.resubmitIncompletePublications { it.identifier == publication }

        await().atMost(Duration.ofSeconds(10)).until { publicationCompleted(jdbc, publication) }
        for (id in listOf(first, second, third, failing)) {
            assertThat(status(id)).isEqualTo("CANCELLED")
            assertThat(publicationIds(jdbc, BookingCancelled::class.java, "bookingId", id, "BookingNotificationListener.on")).hasSize(1)
        }
        for (id in listOf(first, failing)) {
            assertThat(payments.awaitRefundRequested(id).reason).isEqualTo("PERFORMANCE_CANCELLED")
            assertThat(payments.refundRequestsOf(id)).hasSize(1)
            assertThat(collector.of(RefundRequested::class.java) { it.orderId == id }).hasSize(1)
        }
    }

    private fun cancelPerformance(): UUID {
        val event = PerformanceCancelled(UUID.randomUUID(), 17, performanceId, 1, "공연장 사정", Instant.now())
        tx.executeWithoutResult { events.publishEvent(event) }
        return publicationIds(
            jdbc,
            PerformanceCancelled::class.java,
            "performanceId",
            performanceId,
            "ScheduleRegisteredListener.on",
        ).single()
    }

    private fun schedule(performanceId: Long = this.performanceId): Long {
        val id = uniqueId()
        schedules.register(
            RegisterScheduleSeatsCommand(
                id,
                performanceId,
                "공연",
                20,
                Instant.now().plusSeconds(3600),
                (1L..4L).map { RegisterScheduleSeatsCommand.SeatInfo(it, "VIP", "A-$it", BigDecimal.TEN) },
            ),
        )
        return id
    }

    private fun booking(schedule: Long, seat: Long, status: BookingStatus = BookingStatus.PENDING, coupon: Boolean = false): Long {
        val now = Instant.now()
        val scheduleId = ScheduleId(schedule)
        val seatId = SeatId(seat)
        val key = UUID.randomUUID().toString()
        val booking = Booking.create(MemberId(uniqueId()), scheduleId, listOf(BookedSeat(seatId, BigDecimal.TEN)), HoldToken(key), now, key)
        when (status) {
            BookingStatus.PENDING -> Unit
            BookingStatus.EXPIRED -> booking.expire(booking.expiresAt)
            BookingStatus.CANCELLED -> booking.cancel(CancelReason.USER_REQUESTED, now)
            BookingStatus.CONFIRMED -> booking.confirm(now)
        }
        return requireNotNull(
            tx.execute {
                val persisted = bookings.save(booking)
                if (coupon) {
                    persisted.applyCoupon(uniqueId(), now)
                    bookings.save(persisted)
                }
                val id = requireNotNull(persisted.id)
                if (status == BookingStatus.CONFIRMED) bookings.insertSoldSeats(id, scheduleId, listOf(seatId), now)
                id.value
            },
        )
    }

    private fun status(id: Long) = dsl.select(BOOKING.STATUS).from(BOOKING).where(BOOKING.ID.eq(id)).fetchSingle().value1()

    private fun uniqueId() = UUID.randomUUID().mostSignificantBits.ushr(16)
}
