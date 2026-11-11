package io.github.ticketing.e2e

import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.e2e.support.FlowSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class PaymentFailurePathTests : FlowSupport() {
    @Test fun `결제 실패는 예매를 취소하고 좌석을 반환한다`() {
        val schedule = schedule(); val user = token(); val other = token()
        val id = created(book(user, schedule, schedule.seats.take(1)))
        pay(user, id); webhook(id, "PAYMENT_FAILED")
        awaitStatus(user, id, "CANCELLED")
        assertThat(reason(id)).isEqualTo("PAYMENT_FAILED")
        eventually { assertThat(redis.hasKey(hold(schedule, schedule.seats[0]))).isFalse() }
        created(book(other, schedule, schedule.seats.take(1)))
    }
    @Test fun `같은 승인 웹훅은 한 번만 처리한다`() {
        val schedule = schedule(); val user = token()
        val id = created(book(user, schedule, schedule.seats.take(1)))
        pay(user, id)
        val event = UUID.randomUUID().toString()
        repeat(3) { webhook(id, "PAYMENT_APPROVED", eventId = event) }
        awaitStatus(user, id, "CONFIRMED")
        assertThat(collector.of(BookingConfirmed::class.java) { it.bookingId == id }).hasSize(1)
        assertThat(pg.refunds.filter { it.first == "tx-$id" }).isEmpty()
        assertThat(sold(schedule)).isEqualTo(1)
    }
    @Test fun `만료 후 승인되면 환불하고 좌석을 반환한다`() {
        val schedule = schedule(); val user = token(); val other = token()
        val id = created(book(user, schedule, schedule.seats.take(1)))
        pay(user, id); clock.now = clock.now.plus(Duration.ofMinutes(6)); webhook(id, "PAYMENT_APPROVED")
        awaitStatus(user, id, "EXPIRED")
        eventually { assertThat(pg.refunds.filter { it.first == "tx-$id" }).hasSize(1); assertThat(redis.hasKey(hold(schedule, schedule.seats[0]))).isFalse() }
        created(book(other, schedule, schedule.seats.take(1)))
        webhook(id, "REFUND_COMPLETED")
        eventually { assertThat(paymentStatus(id)).isEqualTo("REFUNDED") }
    }
}
