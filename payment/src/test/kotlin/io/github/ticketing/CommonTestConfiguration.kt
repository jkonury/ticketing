package io.github.ticketing

import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.application.port.required.PgTransaction
import io.github.ticketing.payment.application.port.required.PgTransactionQueryPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 모든 통합 테스트가 공유하는 가변 테스트 빈.
 * 컨텍스트를 하나로 유지하기 위해 테스트별 빈 대체 대신 이 빈의 상태를 [IntegrationTestSupport]가 매 테스트 전에 초기화한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class CommonTestConfiguration {
    @Bean
    @Primary
    fun mutableClock() = MutableClock()

    @Bean
    @Primary
    fun testPg() = TestPg()

    @Bean
    @Primary
    fun testPgTransactions() = TestPgTransactions()

    @Bean
    fun sharedTestBeansResetter(clock: MutableClock, pg: TestPg) = SharedTestBeansResetter(clock, pg)
}

class SharedTestBeansResetter(private val clock: MutableClock, private val pg: TestPg) {
    fun reset() {
        clock.reset()
        pg.reset()
    }
}

/** 기본은 실제 시간을 따르고, [now]를 지정하면 그 시각으로 고정된다. */
class MutableClock : Clock() {
    @Volatile private var fixed: Instant? = null

    var now: Instant
        get() = fixed ?: Instant.now()
        set(value) {
            fixed = value
        }

    fun reset() {
        fixed = null
    }

    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

class TestPg : PgPort {
    val requests = CopyOnWriteArrayList<PgPaymentRequest>()
    val refunds = CopyOnWriteArrayList<Pair<String, String>>()
    val transactions = ConcurrentHashMap<String, String>()

    /** 주문 또는 거래 ID별로 호출 시점에 트랜잭션이 활성이었는지 기록한다. */
    val flags = ConcurrentHashMap<String, Boolean>()

    @Volatile var gate: CountDownLatch? = null

    @Volatile var paymentResult = "accepted"

    @Volatile var refundResult = "accepted"

    fun reset() {
        requests.clear()
        refunds.clear()
        transactions.clear()
        flags.clear()
        gate = null
        paymentResult = "accepted"
        refundResult = "accepted"
    }

    override fun requestPayment(request: PgPaymentRequest): PgRequestResult {
        val active = TransactionSynchronizationManager.isActualTransactionActive()
        flags[request.orderId] = active
        check(!active)
        requests += request
        gate?.let { check(it.await(10, TimeUnit.SECONDS)) }
        val transaction = transactions.computeIfAbsent(request.idempotencyKey) { "tx-${request.orderId}" }
        return when {
            paymentResult == "unknown" || request.idempotencyKey.startsWith("unknown") -> PgRequestResult.Unknown("timeout")
            paymentResult == "rejected" -> PgRequestResult.Rejected("declined")
            else -> PgRequestResult.Accepted(transaction)
        }
    }

    override fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult {
        val active = TransactionSynchronizationManager.isActualTransactionActive()
        flags[transactionId] = active
        check(!active)
        val result = refundResult
        refunds += transactionId to idempotencyKey
        return when {
            result == "unknown" -> PgRequestResult.Unknown("timeout")
            result == "rejected" || transactionId.startsWith("reject") -> PgRequestResult.Rejected("declined")
            else -> PgRequestResult.Accepted(transactionId)
        }
    }
}

class TestPgTransactions : PgTransactionQueryPort {
    override fun findTransactions(date: LocalDate): List<PgTransaction> = emptyList()
}
