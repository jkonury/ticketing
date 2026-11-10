package io.github.ticketing.payment

import io.github.ticketing.TEST_WEBHOOK_SECRET
import io.github.ticketing.TestcontainersConfiguration
import io.github.ticketing.payment.application.port.provided.HandlePgWebhookUseCase
import io.github.ticketing.payment.application.port.provided.PgWebhookCommand
import io.github.ticketing.payment.application.port.provided.PgWebhookType
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.domain.OrderId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** payment 빈만 띄운다. PG는 호출을 기록하는 fake로 바꾸고, 웹훅은 컨트롤러 대신 유스케이스를 직접 호출한다. */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class, PaymentModuleTests.FakePgConfig::class)
@TestPropertySource(properties = ["ticketing.payment.fake-pg.webhook-secret=$TEST_WEBHOOK_SECRET"])
class PaymentModuleTests {
    class RecordingPgPort : PgPort {
        val paymentCalls = CopyOnWriteArrayList<PgPaymentRequest>()

        override fun requestPayment(request: PgPaymentRequest): PgRequestResult {
            paymentCalls += request
            return PgRequestResult.Accepted("tx-${request.orderId}")
        }

        override fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult =
            PgRequestResult.Accepted(transactionId)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakePgConfig {
        @Bean
        @Primary
        fun recordingPgPort() = RecordingPgPort()
    }

    @Autowired lateinit var pg: RecordingPgPort

    @Autowired lateinit var payments: PaymentCommandPort

    @Autowired lateinit var webhook: HandlePgWebhookUseCase

    private val amount = BigDecimal(30_000)

    @Test
    fun `PaymentRequested를 발행하면 PG를 호출하고 승인 웹훅을 처리하면 PaymentSucceeded가 발행된다`(scenario: Scenario) {
        val orderId = 910_001L
        scenario.publish(PaymentRequested(orderId, 7, amount, "booking-$orderId", Instant.parse("2026-11-10T00:00:00Z")))
            .andWaitForStateChange<String>({ payments.findByOrderId(OrderId(orderId))?.pgTransactionId ?: "" }, { it == "tx-$orderId" })

        val call = pg.paymentCalls.single { it.orderId == orderId.toString() }
        assertThat(call.idempotencyKey).isEqualTo("booking-$orderId")
        assertThat(call.amount).isEqualByComparingTo(amount)

        val command = PgWebhookCommand("evt-$orderId", PgWebhookType.PAYMENT_APPROVED, "tx-$orderId", orderId, amount, null)
        scenario.stimulate(Runnable { webhook.handle(command) })
            .andWaitForEventOfType(PaymentSucceeded::class.java)
            .matching { it.orderId == orderId }
            .toArriveAndVerify { event -> assertThat(event.amount).isEqualByComparingTo(amount) }
    }
}
