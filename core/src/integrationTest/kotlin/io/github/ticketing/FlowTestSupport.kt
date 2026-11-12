package io.github.ticketing

import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FlowTestSupport {
    class MutableClock : Clock() {
        @Volatile var now: Instant = Instant.now()
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    class EventCollector {
        val events = CopyOnWriteArrayList<Any>()

        @EventListener
        fun on(event: Any) {
            if (event.javaClass.name.startsWith("io.github.ticketing.booking.Booking") ||
                event.javaClass.name.startsWith("io.github.ticketing.payment.")
            ) {
                events += event
            }
        }

        fun <T : Any> of(type: Class<T>, predicate: (T) -> Boolean): List<T> = events.filterIsInstance(type).filter(predicate)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestClockConfig {

        @Bean
        @Primary
        fun mutableClock() = MutableClock()

        @Bean
        @Primary
        fun pg() = TestPg()

        @Bean
        fun eventCollector() = EventCollector()
    }

    class TestPg : PgPort {
        val requests = CopyOnWriteArrayList<PgPaymentRequest>()
        val refunds = CopyOnWriteArrayList<Pair<String, String>>()
        val transactions = ConcurrentHashMap<String, String>()

        @Volatile var gate: CountDownLatch? = null

        @Volatile var paymentResult = "accepted"

        @Volatile var refundResult = "accepted"

        fun reset() {
            gate = null
            paymentResult = "accepted"
            refundResult = "accepted"
        }

        override fun requestPayment(request: PgPaymentRequest): PgRequestResult {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            requests += request
            gate?.let { check(it.await(10, TimeUnit.SECONDS)) }
            val transaction = transactions.computeIfAbsent(request.idempotencyKey) { "tx-${request.orderId}" }
            return when (paymentResult) {
                "unknown" -> PgRequestResult.Unknown("timeout")
                "rejected" -> PgRequestResult.Rejected("declined")
                else -> PgRequestResult.Accepted(transaction)
            }
        }

        override fun requestRefund(transactionId: String, idempotencyKey: String): PgRequestResult {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            refunds += transactionId to idempotencyKey
            return when (refundResult) {
                "unknown" -> PgRequestResult.Unknown("timeout")
                "rejected" -> PgRequestResult.Rejected("declined")
                else -> PgRequestResult.Accepted(transactionId)
            }
        }
    }
}
