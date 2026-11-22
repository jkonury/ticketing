package io.github.ticketing

import io.github.ticketing.booking.application.port.provided.ExpireBookingUseCase
import io.github.ticketing.booking.application.port.provided.ExpireResult
import io.github.ticketing.booking.application.service.ExpireBookingService
import io.github.ticketing.settlement.adapter.required.jooq.DailySettlementJooqAdapter
import io.github.ticketing.settlement.application.port.required.DailySettlementCommandPort
import io.github.ticketing.settlement.domain.DailySettlement
import io.github.ticketing.support.KafkaPaymentStub
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
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

    /** payment 앱 자리에 서는 Kafka 결제 대역. 결제 요청·환불 요청을 소비하고 지정한 결과를 발행한다. */
    @Bean
    fun kafkaPaymentStub(kafkaProperties: KafkaProperties, topics: KafkaTopics, json: JsonMapper) =
        KafkaPaymentStub(kafkaProperties.bootstrapServers, topics, json)

    @Bean
    fun eventCollector() = EventCollector()

    @Bean
    fun sharedTestBeansResetter(
        clock: MutableClock,
        paymentStub: KafkaPaymentStub,
        expireUseCase: FailingExpireBookingUseCase,
        settlementWriter: FailingDailySettlementWriter,
    ) = SharedTestBeansResetter(clock, paymentStub, expireUseCase, settlementWriter)

    @Bean
    @Primary
    fun failingExpireBookingUseCase(delegate: ExpireBookingService) = FailingExpireBookingUseCase(delegate)

    @Bean
    @Primary
    fun failingDailySettlementWriter(adapter: DailySettlementJooqAdapter) = FailingDailySettlementWriter(adapter)
}

class SharedTestBeansResetter(
    private val clock: MutableClock,
    private val paymentStub: KafkaPaymentStub,
    private val expireUseCase: FailingExpireBookingUseCase,
    private val settlementWriter: FailingDailySettlementWriter,
) {
    fun reset() {
        clock.reset()
        paymentStub.reset()
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

@Component
class EventCollector {
    val events = CopyOnWriteArrayList<Any>()

    @EventListener
    fun on(event: Any) {
        val name = event.javaClass.name
        if (COLLECTED_PREFIXES.any { name.startsWith(it) }) {
            events += event
        }
    }

    fun <T : Any> of(type: Class<T>, predicate: (T) -> Boolean): List<T> = events.filterIsInstance(type).filter(predicate)

    private companion object {
        val COLLECTED_PREFIXES = listOf(
            "io.github.ticketing.booking.Booking",
            "io.github.ticketing.payment.",
            "io.github.ticketing.coupon.CouponRe",
            "io.github.ticketing.coupon.CouponUseConfirmed",
        )
    }
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
