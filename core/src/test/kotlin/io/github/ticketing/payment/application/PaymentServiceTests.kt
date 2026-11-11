package io.github.ticketing.payment.application

import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentSucceeded
import io.github.ticketing.payment.application.port.provided.PaymentWebhookCommand
import io.github.ticketing.payment.application.port.provided.PaymentWebhookType
import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.provided.RequestRefundCommand
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.application.service.PaymentPgException
import io.github.ticketing.payment.application.service.PaymentRefundService
import io.github.ticketing.payment.application.service.PaymentRequestService
import io.github.ticketing.payment.application.service.PaymentTransactionService
import io.github.ticketing.payment.application.service.PaymentWebhookService
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import io.github.ticketing.payment.domain.PaymentContractMismatchException
import io.github.ticketing.payment.domain.PaymentId
import io.github.ticketing.payment.domain.PaymentStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PaymentServiceTests {
    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = PaymentStore()
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { event -> published.add(event) }
    private val transactions = PaymentTransactionService(store, publisher, clock)
    private val pg = TestPg()
    private val requests = PaymentRequestService(transactions, pg)
    private val refunds = PaymentRefundService(transactions, pg)
    private val webhooks = PaymentWebhookService(store, publisher, clock)
    private val command = RequestPaymentCommand(1, 2, BigDecimal("100"), "key", now)

    @Test
    fun `미확정 결제는 같은 계약과 키로 재시도하고 충돌은 거부한다`() {
        pg.result = PgRequestResult.Unknown("timeout")
        assertThatThrownBy { requests.request(command) }.isInstanceOf(PaymentPgException::class.java)
        pg.result = PgRequestResult.Accepted("tx")
        requests.request(command)
        requests.request(command)

        assertThat(pg.paymentRequests).containsExactly(
            PgPaymentRequest("1", BigDecimal("100"), "key"),
            PgPaymentRequest("1", BigDecimal("100"), "key"),
        )
        assertThat(store.items).hasSize(1)
        assertThatThrownBy { requests.request(command.copy(amount = BigDecimal.TEN)) }
            .isInstanceOf(PaymentContractMismatchException::class.java)
        assertThatThrownBy { requests.request(command.copy(orderId = 3)) }
            .isInstanceOf(PaymentContractMismatchException::class.java)
    }

    @Test
    fun `거절은 실패 이벤트를 발행하고 지연된 거절은 승인을 덮어쓰지 않는다`() {
        pg.result = PgRequestResult.Rejected("declined")
        requests.request(command)
        assertThat(store.items.single().status).isEqualTo(PaymentStatus.FAILED)
        assertThat(published.single()).isEqualTo(PaymentFailed(1, 1, "declined", now))

        val other = command.copy(orderId = 3, idempotencyKey = "other")
        transactions.preparePayment(other)
        webhooks.handle(webhook("event", PaymentWebhookType.PAYMENT_APPROVED, orderId = 3))
        transactions.rejectPayment(3, "late")
        transactions.acceptPayment(3, "tx")
        assertThat(store.items.last().status).isEqualTo(PaymentStatus.SUCCEEDED)
        assertThat(published.filterIsInstance<PaymentFailed>()).hasSize(1)
    }

    @Test
    fun `승인 웹훅 중복과 서로 다른 이벤트의 동일 결과는 한 번만 발행한다`() {
        transactions.preparePayment(command)
        val approved = webhook("event", PaymentWebhookType.PAYMENT_APPROVED)
        webhooks.handle(approved)
        webhooks.handle(approved)
        webhooks.handle(approved.copy(eventId = "another", occurredAt = now.plusSeconds(1)))
        assertThat(store.items.single().approvedAt).isEqualTo(now)
        assertThat(published).containsExactly(PaymentSucceeded(1, 1, BigDecimal("100"), now))
    }

    @Test
    fun `웹훅 계약 불일치와 잘못된 실패 사유를 거부한다`() {
        transactions.preparePayment(command)
        assertThatThrownBy { webhooks.handle(webhook("wrong", PaymentWebhookType.PAYMENT_APPROVED).copy(amount = BigDecimal.ONE)) }
            .isInstanceOf(PaymentContractMismatchException::class.java)
        assertThatThrownBy { webhooks.handle(webhook("bad", PaymentWebhookType.PAYMENT_FAILED).copy(failureReason = " ")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(published).isEmpty()
    }

    @Test
    fun `환불 거절과 미확정은 같은 키로 재시도하고 완료 후 재호출하지 않는다`() {
        transactions.preparePayment(command)
        webhooks.handle(webhook("approved", PaymentWebhookType.PAYMENT_APPROVED))
        val refund = RequestRefundCommand(1, "cancel", now)
        pg.result = PgRequestResult.Rejected("declined")
        assertThatThrownBy { refunds.request(refund) }.isInstanceOf(PaymentPgException::class.java)
        pg.result = PgRequestResult.Unknown("timeout")
        assertThatThrownBy { refunds.request(refund) }.isInstanceOf(PaymentPgException::class.java)
        pg.result = PgRequestResult.Accepted("tx")
        refunds.request(refund)
        val completed = webhook("refunded", PaymentWebhookType.REFUND_COMPLETED)
        webhooks.handle(completed)
        webhooks.handle(completed.copy(eventId = "refunded-again"))
        refunds.request(refund)
        assertThat(pg.refundRequests).containsExactly("tx" to "refund-1", "tx" to "refund-1", "tx" to "refund-1")
        assertThat(store.items.single().status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(published.filterIsInstance<PaymentRefunded>()).containsExactly(PaymentRefunded(1, 1, now))
    }

    private fun webhook(eventId: String, type: PaymentWebhookType, orderId: Long = 1) =
        PaymentWebhookCommand(eventId, type, "tx", orderId, BigDecimal("100"), now, "declined")
}

private class PaymentStore : PaymentCommandPort {
    val items = mutableListOf<Payment>()
    private val events = mutableSetOf<String>()

    override fun save(payment: Payment): Payment {
        val saved = if (payment.id == null) {
            Payment.restore(
                PaymentId((items.size + 1).toLong()), payment.orderId, payment.memberId, payment.amount, payment.idempotencyKey,
                payment.requestedAt, 0, payment.status, payment.pgTransactionId, payment.failureReason, payment.approvedAt,
                payment.failedAt, payment.refundRequestedAt, payment.refundedAt,
            )
        } else {
            payment
        }
        items.removeAll { it.orderId == saved.orderId }
        items.add(saved)
        return saved
    }

    override fun findByOrderId(orderId: OrderId): Payment? = items.find { it.orderId == orderId }
    override fun findByIdempotencyKey(key: String): Payment? = items.find { it.idempotencyKey == key }
    override fun findByPgTransactionId(transactionId: String): Payment? = items.find { it.pgTransactionId == transactionId }
    override fun recordWebhookEvent(eventId: String, type: String, transactionId: String, now: Instant): Boolean = events.add(eventId)
}

private class TestPg : PgPort {
    var result: PgRequestResult = PgRequestResult.Accepted("tx")
    val paymentRequests = mutableListOf<PgPaymentRequest>()
    val refundRequests = mutableListOf<Pair<String, String>>()

    override fun requestPayment(request: PgPaymentRequest): PgRequestResult {
        paymentRequests.add(request)
        return result
    }

    override fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult {
        refundRequests.add(transactionId to idempotencyKey)
        return result
    }
}
