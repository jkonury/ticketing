package io.github.ticketing

import io.github.ticketing.booking.application.port.provided.ExpireBookingUseCase
import io.github.ticketing.booking.application.port.provided.ExpireResult
import io.github.ticketing.booking.application.service.ExpireBookingService
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.application.port.required.PgTransaction
import io.github.ticketing.payment.application.port.required.PgTransactionQueryPort
import io.github.ticketing.settlement.adapter.required.jooq.DailySettlementJooqAdapter
import io.github.ticketing.settlement.application.port.required.DailySettlementCommandPort
import io.github.ticketing.settlement.domain.DailySettlement
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    fun eventCollector() = EventCollector()

    @Bean
    fun sharedTestBeansResetter(
        clock: MutableClock,
        pg: TestPg,
        pgTransactions: TestPgTransactions,
        expireUseCase: FailingExpireBookingUseCase,
        settlementWriter: FailingDailySettlementWriter,
    ) = SharedTestBeansResetter(clock, pg, pgTransactions, expireUseCase, settlementWriter)

    @Bean
    @Primary
    fun failingExpireBookingUseCase(delegate: ExpireBookingService) = FailingExpireBookingUseCase(delegate)

    @Bean
    @Primary
    fun failingDailySettlementWriter(adapter: DailySettlementJooqAdapter) = FailingDailySettlementWriter(adapter)
}

class SharedTestBeansResetter(
    private val clock: MutableClock,
    private val pg: TestPg,
    private val pgTransactions: TestPgTransactions,
    private val expireUseCase: FailingExpireBookingUseCase,
    private val settlementWriter: FailingDailySettlementWriter,
) {
    fun reset() {
        clock.reset()
        pg.reset()
        pgTransactions.reset()
        expireUseCase.reset()
        settlementWriter.reset()
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
        refunds += transactionId to idempotencyKey
        return when {
            refundResult == "unknown" -> PgRequestResult.Unknown("timeout")
            refundResult == "rejected" || transactionId.startsWith("reject") -> PgRequestResult.Rejected("declined")
            else -> PgRequestResult.Accepted(transactionId)
        }
    }
}

class TestPgTransactions : PgTransactionQueryPort {
    var transactions: List<PgTransaction> = emptyList()
    var fail = false

    fun reset() {
        transactions = emptyList()
        fail = false
    }

    override fun findTransactions(date: LocalDate): List<PgTransaction> {
        check(!fail) { "의도된 PG 조회 실패" }
        return transactions
    }
}

@Component
class EventCollector {
    val events = CopyOnWriteArrayList<Any>()

    @EventListener
    fun on(event: Any) {
        if (event.javaClass.name.startsWith("io.github.ticketing.booking.Booking") ||
            event.javaClass.name.startsWith("io.github.ticketing.payment.") ||
            event.javaClass.name.startsWith("io.github.ticketing.coupon.CouponRe")
        ) {
            events += event
        }
    }

    fun <T : Any> of(type: Class<T>, predicate: (T) -> Boolean): List<T> = events.filterIsInstance(type).filter(predicate)
}

class FailingExpireBookingUseCase(private val delegate: ExpireBookingService) : ExpireBookingUseCase {
    var failingId = 0L
    val failNext = AtomicBoolean(false)

    fun reset() {
        failingId = 0L
        failNext.set(false)
    }

    override fun expireIfDue(bookingId: Long): ExpireResult {
        if (bookingId == failingId && failNext.compareAndSet(true, false)) {
            throw OptimisticLockingFailureException("동시 수정")
        }
        return delegate.expireIfDue(bookingId)
    }
}

class FailingDailySettlementWriter(private val delegate: DailySettlementJooqAdapter) : DailySettlementCommandPort {
    val failNext = AtomicBoolean(false)
    var failingPerformance = 0L

    fun reset() {
        failNext.set(false)
        failingPerformance = 0L
    }

    override fun upsertAll(settlements: List<DailySettlement>) {
        if (settlements.any { it.performanceId == failingPerformance } && failNext.compareAndSet(true, false)) {
            error("의도된 두 번째 청크 실패")
        }
        delegate.upsertAll(settlements)
    }
}
