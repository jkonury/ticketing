package io.github.ticketing.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PaymentTests {
    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val amount = BigDecimal(20_000)

    private fun requested() = Payment.request(OrderId(1), MemberId(2), amount, "key-1", now)

    private fun succeeded() = requested().also { it.approve(amount, now) }

    @Test
    fun `요청하면 REQUESTED 상태로 생성된다`() {
        val p = requested()
        assertThat(p.status).isEqualTo(PaymentStatus.REQUESTED)
        assertThat(p.id).isNull()
    }

    @Test
    fun `승인하면 SUCCEEDED가 되고 같은 승인은 AlreadyApplied다`() {
        val p = requested()
        assertThat(p.approve(amount, now)).isEqualTo(TransitionResult.Applied)
        assertThat(p.status).isEqualTo(PaymentStatus.SUCCEEDED)
        assertThat(p.approvedAt).isEqualTo(now)
        assertThat(p.approve(amount, now.plusSeconds(5))).isEqualTo(TransitionResult.AlreadyApplied)
        assertThat(p.approvedAt).isEqualTo(now)
    }

    @Test
    fun `승인 금액이 다르면 예외를 던지고 상태를 바꾸지 않는다`() {
        val p = requested()
        assertThatThrownBy { p.approve(BigDecimal(1), now) }.isInstanceOf(PaymentAmountMismatchException::class.java)
        assertThat(p.status).isEqualTo(PaymentStatus.REQUESTED)
    }

    @Test
    fun `승인 금액은 스케일이 달라도 값이 같으면 일치한다`() {
        assertThat(requested().approve(BigDecimal("20000.00"), now)).isEqualTo(TransitionResult.Applied)
    }

    @Test
    fun `실패 처리하면 FAILED가 되고 중복 실패는 AlreadyApplied다`() {
        val p = requested()
        assertThat(p.fail("한도 초과", now)).isEqualTo(TransitionResult.Applied)
        assertThat(p.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(p.failureReason).isEqualTo("한도 초과")
        assertThat(p.fail("한도 초과", now)).isEqualTo(TransitionResult.AlreadyApplied)
    }

    @Test
    fun `FAILED에서 승인하면 INVALID_PAYMENT_STATE 예외다`() {
        val p = requested().also { it.fail("x", now) }
        assertThatThrownBy { p.approve(amount, now) }
            .isInstanceOf(InvalidPaymentStateException::class.java)
            .extracting { (it as PaymentRuleException).code }.isEqualTo(INVALID_PAYMENT_STATE)
    }

    @Test
    fun `SUCCEEDED에서 실패 처리하면 예외다`() {
        assertThatThrownBy { succeeded().fail("x", now) }.isInstanceOf(InvalidPaymentStateException::class.java)
    }

    @Test
    fun `환불 요청은 SUCCEEDED에서만 가능하고 중복은 AlreadyApplied다`() {
        val p = succeeded()
        assertThat(p.requestRefund(now)).isEqualTo(TransitionResult.Applied)
        assertThat(p.status).isEqualTo(PaymentStatus.REFUND_REQUESTED)
        assertThat(p.requestRefund(now)).isEqualTo(TransitionResult.AlreadyApplied)
    }

    @Test
    fun `REQUESTED에서 환불을 요청하면 예외다`() {
        assertThatThrownBy { requested().requestRefund(now) }.isInstanceOf(InvalidPaymentStateException::class.java)
    }

    @Test
    fun `FAILED에서 환불을 요청하면 예외다`() {
        val p = requested().also { it.fail("x", now) }
        assertThatThrownBy { p.requestRefund(now) }.isInstanceOf(InvalidPaymentStateException::class.java)
    }

    @Test
    fun `환불 완료는 REFUND_REQUESTED와 SUCCEEDED에서 가능하다`() {
        val a = succeeded().also { it.requestRefund(now) }
        assertThat(a.completeRefund(now)).isEqualTo(TransitionResult.Applied)
        assertThat(a.status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(a.completeRefund(now)).isEqualTo(TransitionResult.AlreadyApplied)

        val b = succeeded()
        assertThat(b.completeRefund(now)).isEqualTo(TransitionResult.Applied)
        assertThat(b.refundedAt).isEqualTo(now)
    }

    @Test
    fun `REQUESTED에서 환불을 완료하면 예외다`() {
        assertThatThrownBy { requested().completeRefund(now) }.isInstanceOf(InvalidPaymentStateException::class.java)
    }

    @Test
    fun `REFUNDED에서 승인하면 예외다`() {
        val p = succeeded().also { it.completeRefund(now) }
        assertThatThrownBy { p.approve(amount, now) }.isInstanceOf(InvalidPaymentStateException::class.java)
    }

    @Test
    fun `PG 거래 ID는 같은 값이면 무시하고 다른 값이면 예외다`() {
        val p = requested()
        p.attachPgTransaction("tx-1")
        p.attachPgTransaction("tx-1")
        assertThat(p.pgTransactionId).isEqualTo("tx-1")
        assertThatThrownBy { p.attachPgTransaction("tx-2") }.isInstanceOf(PgTransactionConflictException::class.java)
    }
}
