package io.github.ticketing.payment.application

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.payment.PaymentFailed
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
import io.github.ticketing.payment.domain.PaymentContractMismatchException
import io.github.ticketing.payment.domain.PaymentStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RecordApplicationEvents
class PaymentApplicationTests : IntegrationTestSupport() {

    @Autowired
    lateinit var transactions: PaymentTransactionService

    @Autowired
    lateinit var webhook: PaymentWebhookService

    @Autowired
    lateinit var payments: PaymentCommandPort

    @Autowired
    lateinit var tx: TransactionTemplate

    @Autowired
    lateinit var events: ApplicationEvents

    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private val order = ids.incrementAndGet()
    private val key = UUID.randomUUID().toString()
    private val command get() = RequestPaymentCommand(order, 1, BigDecimal("100"), key, now)

    private fun find() = requireNotNull(tx.execute { payments.findByOrderId(OrderId(order)) })

    private fun event(
        type: PaymentWebhookType,
        id: String = UUID.randomUUID().toString(),
        transaction: String = "tx-$key",
        amount: BigDecimal = BigDecimal("100"),
        reason: String? = null,
    ) = PaymentWebhookCommand(id, type, transaction, order, amount, now, reason)

    private inner class FakePg : PgPort {

        var payment: (PgPaymentRequest) -> PgRequestResult = { PgRequestResult.Accepted("tx-$key") }
        var refund: (String, String) -> PgRequestResult = { _, _ -> PgRequestResult.Accepted("tx-$key") }

        override fun requestPayment(request: PgPaymentRequest): PgRequestResult {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(find().status).isEqualTo(PaymentStatus.REQUESTED)
            return payment(request)
        }

        override fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(find().status).isEqualTo(PaymentStatus.REFUND_REQUESTED)
            return refund(transactionId, idempotencyKey)
        }
    }

    @Test
    fun `미확정 결제는 같은 키로 재시도하고 계약 충돌은 거부한다`() {
        val pg = FakePg()
        val service = PaymentRequestService(transactions, pg)
        val requests = mutableListOf<PgPaymentRequest>()
        pg.payment =
            {
                requests.add(it)
                if (requests.size == 1) PgRequestResult.Unknown("TIMEOUT") else PgRequestResult.Accepted("tx-$key")
            }

        assertThatThrownBy { service.request(command) }.isInstanceOf(PaymentPgException::class.java)
        assertThat(find().status).isEqualTo(PaymentStatus.REQUESTED)

        service.request(command)
        service.request(command)

        assertThat(requests).hasSize(2)
        assertThat(requests[0]).isEqualTo(requests[1])
        assertThatThrownBy {
            service.request(command.copy(amount = BigDecimal("101")))
        }.isInstanceOf(PaymentContractMismatchException::class.java)
        assertThatThrownBy {
            service.request(command.copy(orderId = ids.incrementAndGet()))
        }.isInstanceOf(PaymentContractMismatchException::class.java)
    }

    @Test
    fun `지연된 거절은 먼저 승인된 웹훅을 덮어쓰지 않는다`() {
        val pg = FakePg()
        pg.payment = {
            webhook.handle(event(PaymentWebhookType.PAYMENT_APPROVED))
            PgRequestResult.Rejected("DECLINED")
        }

        PaymentRequestService(transactions, pg).request(command)

        assertThat(find().status).isEqualTo(PaymentStatus.SUCCEEDED)
        assertThat(events.stream(PaymentSucceeded::class.java).filter { it.orderId == order }.count()).isEqualTo(1)
        assertThat(events.stream(PaymentFailed::class.java).filter { it.orderId == order }.count()).isZero()
    }

    @Test
    fun `웹훅 실패 기록은 롤백하고 동일 결과는 한 번만 발행한다`() {
        transactions.preparePayment(command)
        val id = UUID.randomUUID().toString()

        assertThatThrownBy {
            webhook.handle(event(PaymentWebhookType.PAYMENT_APPROVED, id, amount = BigDecimal("101")))
        }.isInstanceOf(PaymentContractMismatchException::class.java)

        webhook.handle(event(PaymentWebhookType.PAYMENT_APPROVED, id))
        webhook.handle(event(PaymentWebhookType.PAYMENT_APPROVED, id))
        webhook.handle(event(PaymentWebhookType.PAYMENT_APPROVED))

        assertThat(events.stream(PaymentSucceeded::class.java).filter { it.orderId == order }.count()).isEqualTo(1)
        assertThat(find().status).isEqualTo(PaymentStatus.SUCCEEDED)
    }

    @Test
    fun `환불 거절과 미확정은 같은 키로 복구하고 완료 후 재호출하지 않는다`() {
        val pg = FakePg()
        PaymentRequestService(transactions, pg).request(command)
        webhook.handle(event(PaymentWebhookType.PAYMENT_APPROVED))

        val keys = mutableListOf<String>()
        pg.refund = { _, refundKey ->
            keys.add(refundKey)
            when (keys.size) {
                1 -> PgRequestResult.Rejected("DECLINED")
                2 -> PgRequestResult.Unknown("TIMEOUT")
                else -> PgRequestResult.Accepted("tx-$key")
            }
        }
        val service = PaymentRefundService(transactions, pg)
        val refund = RequestRefundCommand(order, "TEST", now)

        assertThatThrownBy { service.request(refund) }.isInstanceOf(PaymentPgException::class.java)
        assertThatThrownBy { service.request(refund) }.isInstanceOf(PaymentPgException::class.java)
        service.request(refund)

        webhook.handle(event(PaymentWebhookType.REFUND_COMPLETED))
        webhook.handle(event(PaymentWebhookType.PAYMENT_APPROVED))
        service.request(refund)

        assertThat(keys).containsExactly("refund-$order", "refund-$order", "refund-$order")
        assertThat(find().status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(events.stream(PaymentSucceeded::class.java).filter { it.orderId == order }.count()).isEqualTo(1)
    }

    @Test
    fun `실패 웹훅의 잘못된 입력은 중복 기록을 롤백한다`() {
        transactions.preparePayment(command)
        val id = UUID.randomUUID().toString()

        assertThatThrownBy {
            webhook.handle(event(PaymentWebhookType.PAYMENT_FAILED, id))
        }.isInstanceOf(IllegalArgumentException::class.java)

        webhook.handle(event(PaymentWebhookType.PAYMENT_FAILED, id, reason = "DECLINED"))
        webhook.handle(event(PaymentWebhookType.PAYMENT_FAILED, reason = "DECLINED"))

        assertThat(find().status).isEqualTo(PaymentStatus.FAILED)
        assertThat(events.stream(PaymentFailed::class.java).filter { it.orderId == order }.count()).isEqualTo(1)
    }

    @Test
    fun `지연된 접수 응답과 웹훅 중복은 승인 상태를 유지한다`() {
        val pg = FakePg()
        pg.payment = {
            webhook.handle(event(PaymentWebhookType.PAYMENT_APPROVED))
            PgRequestResult.Accepted("tx-$key")
        }

        PaymentRequestService(transactions, pg).request(command)

        assertThat(find().status).isEqualTo(PaymentStatus.SUCCEEDED)
        assertThat(find().pgTransactionId).isEqualTo("tx-$key")
        assertThat(events.stream(PaymentSucceeded::class.java).filter { it.orderId == order }.count()).isEqualTo(1)
    }

    @Test
    fun `이벤트 발행 실패는 상태와 웹훅 기록을 함께 롤백한다`() {
        transactions.preparePayment(command)
        val id = UUID.randomUUID().toString()
        val failing = PaymentWebhookService(payments, { throw IllegalStateException("event failure") }, Clock.fixed(now, ZoneOffset.UTC))

        assertThatThrownBy {
            tx.executeWithoutResult { failing.handle(event(PaymentWebhookType.PAYMENT_APPROVED, id)) }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(find().status).isEqualTo(PaymentStatus.REQUESTED)

        webhook.handle(event(PaymentWebhookType.PAYMENT_APPROVED, id))

        assertThat(find().status).isEqualTo(PaymentStatus.SUCCEEDED)
    }

    companion object {
        private val ids = AtomicLong(System.nanoTime() % 1_000_000_000L * 100)
    }
}
