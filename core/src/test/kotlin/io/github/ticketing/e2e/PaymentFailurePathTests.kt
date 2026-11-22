package io.github.ticketing.e2e

import io.github.ticketing.EventCollector
import io.github.ticketing.MutableClock
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.e2e.support.FlowSupport
import io.github.ticketing.payment.RefundRequested
import io.github.ticketing.support.KafkaPaymentStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Duration
import java.time.Instant

/**
 * 결제 결과는 Kafka 결제 대역이 payment 앱 자리에서 발행한다. 실제 웹훅 서명·멱등(같은 웹훅 재전송)은 payment 앱의 책임이라
 * 이 클래스는 booking이 같은 결과 이벤트를 두 번 받는 경우만 검증하고, 웹훅 멱등은 `e2e` 모듈이 맡는다.
 */
class PaymentFailurePathTests(
    mockMvcTester: MockMvcTester,
    jdbc: JdbcTemplate,
    redis: StringRedisTemplate,
    payments: KafkaPaymentStub,
    clock: MutableClock,
    collector: EventCollector,
) : FlowSupport(mockMvcTester, jdbc, redis, payments, clock, collector) {

    @Test
    fun `결제 실패는 예매를 취소하고 좌석을 반환한다`() {
        val schedule = schedule()
        val user = token()
        val other = token()
        val id = created(book(user, schedule, schedule.seats.take(1)))

        pay(user, id)
        payments.publishFailed(id)

        awaitStatus(user, id, "CANCELLED")
        assertThat(reason(id)).isEqualTo("PAYMENT_FAILED")
        eventually { assertThat(redis.hasKey(hold(schedule, schedule.seats[0]))).isFalse() }

        created(book(other, schedule, schedule.seats.take(1)))
    }

    @Test
    fun `같은 결제 성공 이벤트를 두 번 받아도 한 번만 확정한다`() {
        val schedule = schedule()
        val user = token()
        val id = created(book(user, schedule, schedule.seats.take(1)))

        pay(user, id)

        // 같은 JSON이 두 번 전달되도록 결과 시각을 한 번만 정한다
        val paidAt = Instant.now()
        repeat(2) { payments.publishSucceeded(id, paidAt = paidAt) }
        awaitStatus(user, id, "CONFIRMED")
        payments.awaitResultsConsumedByBooking(payments.topics.paymentSucceeded, id, 2)

        assertThat(collector.of(BookingConfirmed::class.java) { it.bookingId == id }).hasSize(1)
        // 외부화 전송이 늦어도 놓치지 않도록 core 안에서 발행한 RefundRequested가 없었음을 먼저 확인한다
        assertThat(collector.of(RefundRequested::class.java) { it.orderId == id }).isEmpty()
        assertThat(payments.refundRequestsOf(id)).isEmpty()
        assertThat(sold(schedule)).isEqualTo(1)
    }

    @Test
    fun `만료 후 승인되면 환불을 요청하고 좌석을 반환한다`() {
        val schedule = schedule()
        val user = token()
        val other = token()
        val id = created(book(user, schedule, schedule.seats.take(1)))
        payments.respondToRefund(id)

        pay(user, id)
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        payments.publishSucceeded(id)

        awaitStatus(user, id, "EXPIRED")
        eventually {
            assertThat(payments.refundRequestsOf(id)).hasSize(1)
            assertThat(redis.hasKey(hold(schedule, schedule.seats[0]))).isFalse()
        }

        created(book(other, schedule, schedule.seats.take(1)))
        payments.awaitResultsConsumedByBooking(payments.topics.paymentRefunded, id, 1)

        assertThat(detail(user, id)["status"]).isEqualTo("EXPIRED")
        assertThat(sold(schedule)).isZero()
    }
}
