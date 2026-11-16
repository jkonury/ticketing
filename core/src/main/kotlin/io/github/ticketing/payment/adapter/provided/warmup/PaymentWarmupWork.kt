package io.github.ticketing.payment.adapter.provided.warmup

import io.github.ticketing.payment.PaymentWarmupRequested
import io.github.ticketing.payment.adapter.provided.web.PaymentWebhookController
import io.github.ticketing.payment.adapter.required.pg.PaymentPgProperties
import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.required.PgTransactionQueryPort
import io.github.ticketing.payment.application.service.PaymentTransactionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val MAX_RUNS = 5000
private const val WARMUP_ORDER_BASE = 1L shl 62
private const val WARMUP_ORDER_SPAN = 1L shl 40
private const val LOG_INTERVAL = 50

/**
 * 결제 요청·웹훅 처리·PG 송신 경로의 예열이다. 결제 도메인은 ID가 양수여야 하므로 실제 주문 ID가 닿을 수 없는 최상위 구간
 * (2^62 이상)을 쓰고, DB 쓰기는 모두 롤백 전용 트랜잭션에 가둔다. 그 안에서 만든 이벤트(PaymentSucceeded)는 AFTER_COMMIT
 * 리스너가 실행되지 않는다. PG에는 결제·환불 요청을 보내지 않고 부작용 없는 거래 조회(GET)만 같은 RestClient로 보낸다.
 */
@Component
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class PaymentWarmupWork(
    private val transactions: PaymentTransactionService,
    private val webhook: PaymentWebhookController,
    private val pg: PgTransactionQueryPort,
    private val properties: PaymentPgProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val runs = AtomicInteger()
    private val probes = AtomicInteger()
    val pgProbes: Int get() = probes.get()
    private val rollback = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        timeout = 2
    }

    @EventListener(PaymentWarmupRequested::class)
    fun onWarmupRequested() {
        val sequence = runs.incrementAndGet()
        if (sequence > MAX_RUNS) return
        val failure = runCatching { run() }.exceptionOrNull()
        if (failure != null) logger.warn("결제 예열 실패: {}", failure.javaClass.simpleName)
        if (sequence % LOG_INTERVAL == 0) logger.info("payment-warmup runs={} pgProbes={}", sequence, probes.get())
    }

    fun run() {
        val orderId = WARMUP_ORDER_BASE + java.util.concurrent.ThreadLocalRandom.current().nextLong(WARMUP_ORDER_SPAN)
        val transactionId = "txn_warmup_${UUID.randomUUID().toString().replace("-", "")}"
        val amount = BigDecimal.TEN
        val now = clock.instant()
        rollback.execute { status ->
            try {
                transactions.preparePayment(RequestPaymentCommand(orderId, orderId, amount, "warmup-$orderId", now))
                transactions.acceptPayment(orderId, transactionId)
                val body = approvedBody(orderId, transactionId, amount, now.toString()).toByteArray(UTF_8)
                val timestamp = now.epochSecond.toString()
                webhook.receive(timestamp, sign(timestamp, body), body)
            } finally {
                status.setRollbackOnly()
            }
        }
        // 트랜잭션 밖에서 부작용 없는 조회만 보낸다. 거래가 없는 날짜라 빈 목록이 돌아온다.
        pg.findTransactions(LocalDate.EPOCH)
        probes.incrementAndGet()
    }

    private fun approvedBody(orderId: Long, transactionId: String, amount: BigDecimal, occurredAt: String): String =
        """{"eventId":"evt_warmup_${UUID.randomUUID()}","type":"PAYMENT_APPROVED","transactionId":"$transactionId",""" +
            """"orderId":"$orderId","amount":${amount.toBigInteger()},"occurredAt":"$occurredAt"}"""

    private fun sign(timestamp: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.webhookSecret.toByteArray(UTF_8), "HmacSHA256"))
        mac.update(timestamp.toByteArray(UTF_8))
        mac.update('.'.code.toByte())
        return HexFormat.of().formatHex(mac.doFinal(body))
    }
}
