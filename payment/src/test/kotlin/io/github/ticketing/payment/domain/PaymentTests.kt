package io.github.ticketing.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PaymentTests {

    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private fun payment() = Payment.request(OrderId(1), MemberId(2), BigDecimal("100"), "key", now)

    @Test
    fun `승인과 환불은 중복 수신해도 최초 시각을 보존한다`() {
        val requestedPayment = payment()

        assertThat(requestedPayment.approve(BigDecimal("100.0"), now)).isEqualTo(TransitionResult.Applied)
        assertThat(requestedPayment.approve(BigDecimal("100"), now.plusSeconds(1))).isEqualTo(TransitionResult.AlreadyApplied)
        assertThat(requestedPayment.requestRefund(now.plusSeconds(2))).isEqualTo(TransitionResult.Applied)
        assertThat(requestedPayment.requestRefund(now.plusSeconds(3))).isEqualTo(TransitionResult.AlreadyApplied)
        assertThat(requestedPayment.completeRefund(now.plusSeconds(4))).isEqualTo(TransitionResult.Applied)
        assertThat(requestedPayment.completeRefund(now.plusSeconds(5))).isEqualTo(TransitionResult.AlreadyApplied)
        assertThat(requestedPayment.approve(BigDecimal("100"), now.plusSeconds(6))).isEqualTo(TransitionResult.AlreadyApplied)
        assertThat(requestedPayment.requestRefund(now.plusSeconds(7))).isEqualTo(TransitionResult.AlreadyApplied)

        assertThat(requestedPayment.approvedAt).isEqualTo(now)
        assertThat(requestedPayment.refundRequestedAt).isEqualTo(now.plusSeconds(2))
        assertThat(requestedPayment.refundedAt).isEqualTo(now.plusSeconds(4))
    }

    @Test
    fun `실패 재수신은 최초 사유와 시각을 보존한다`() {
        val failedPayment = payment()

        assertThat(failedPayment.fail("first", now)).isEqualTo(TransitionResult.Applied)
        assertThat(failedPayment.fail("second", now.plusSeconds(1))).isEqualTo(TransitionResult.AlreadyApplied)

        assertThat(failedPayment.failureReason).isEqualTo("first")
        assertThat(failedPayment.failedAt).isEqualTo(now)
    }

    @Test
    fun `허용되지 않은 모든 상태 전이는 거부된다`() {
        for (status in PaymentStatus.entries) {
            fun restored() = Payment.restore(
                PaymentId(
                    1,
                ),
                OrderId(1), MemberId(1), BigDecimal("100"), "key", now, 0, status, null, null, null, null, null, null,
            )

            if (status ==
                PaymentStatus.FAILED
            ) {
                assertThatThrownBy { restored().approve(BigDecimal("100"), now) }.isInstanceOf(InvalidPaymentStateException::class.java)
            }
            if (status !in
                listOf(PaymentStatus.REQUESTED, PaymentStatus.FAILED)
            ) {
                assertThatThrownBy { restored().fail("x", now) }.isInstanceOf(InvalidPaymentStateException::class.java)
            }
            if (status in
                listOf(PaymentStatus.REQUESTED, PaymentStatus.FAILED)
            ) {
                assertThatThrownBy { restored().requestRefund(now) }.isInstanceOf(InvalidPaymentStateException::class.java)
            }
            if (status !in
                listOf(PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUNDED)
            ) {
                assertThatThrownBy { restored().completeRefund(now) }.isInstanceOf(InvalidPaymentStateException::class.java)
            }
        }
    }

    @Test
    fun `금액 식별자 멱등 키와 거래 계약을 검증한다`() {
        listOf("0", "-1", "1.1", "1000000000000").forEach { amount ->
            assertThatThrownBy {
                Payment.request(OrderId(1), MemberId(1), BigDecimal(amount), "key", now)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf("", " ", "x".repeat(101)).forEach { key ->
            assertThatThrownBy {
                Payment.request(OrderId(1), MemberId(1), BigDecimal.ONE, key, now)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { PaymentId(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { OrderId(-1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MemberId(0) }.isInstanceOf(IllegalArgumentException::class.java)

        val requestedPayment = payment()

        assertThatThrownBy { requestedPayment.approve(BigDecimal.ONE, now) }.isInstanceOf(PaymentContractMismatchException::class.java)

        requestedPayment.attachPgTransaction("tx")
        requestedPayment.attachPgTransaction("tx")

        assertThatThrownBy { requestedPayment.attachPgTransaction("other") }.isInstanceOf(PaymentContractMismatchException::class.java)
    }
}
