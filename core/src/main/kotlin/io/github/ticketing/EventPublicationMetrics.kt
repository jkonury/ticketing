package io.github.ticketing

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference

/**
 * Modulith 이벤트 발행 레지스트리(`public.event_publication`)의 미완료 건수와 최고령 경과 시간을 지표로 노출한다.
 *
 * 스크레이프마다 조회하지 않도록 [CACHE_TTL] 동안 결과를 재사용한다.
 * 같은 DB를 쓰는 core-batch가 중복 수집하지 않게 batch 프로필은 `ticketing.event-publication.metrics.enabled=false`로 끈다.
 */
@Component
@ConditionalOnProperty("ticketing.event-publication.metrics.enabled", matchIfMissing = true)
class EventPublicationMetrics(private val dsl: DSLContext, private val clock: Clock, registry: MeterRegistry) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val cached = AtomicReference<Snapshot?>(null)

    init {
        Gauge.builder(INCOMPLETE_COUNT) { snapshot()?.count?.toDouble() ?: Double.NaN }
            .description("완료되지 않은 이벤트 발행 건수 (completion_date IS NULL)")
            .register(registry)
        Gauge.builder(OLDEST_AGE) { oldestAgeSeconds() }
            .description("가장 오래된 미완료 이벤트 발행의 경과 시간. 미완료가 없으면 0")
            .baseUnit("seconds")
            .register(registry)
    }

    private fun oldestAgeSeconds(): Double {
        val snapshot = snapshot() ?: return Double.NaN
        val age = snapshot.oldestPublicationDate?.let { Duration.between(it, clock.instant()).toMillis() } ?: 0L
        return age.coerceAtLeast(0) / MILLIS_PER_SECOND
    }

    private fun snapshot(): Snapshot? {
        val now = clock.instant()
        cached.get()?.takeIf { Duration.between(it.loadedAt, now) < CACHE_TTL }?.let { return it }

        return try {
            val record = dsl.select(DSL.count(), DSL.min(PUBLICATION_DATE))
                .from(TABLE)
                .where(COMPLETION_DATE.isNull)
                .fetchSingle()
            Snapshot(now, record.value1(), record.value2()?.toInstant()).also { cached.set(it) }
        } catch (exception: DataAccessException) {
            logger.warn("이벤트 발행 레지스트리 지표 조회 실패", exception)
            cached.get()
        }
    }

    private data class Snapshot(val loadedAt: Instant, val count: Int, val oldestPublicationDate: Instant?)

    companion object {
        const val INCOMPLETE_COUNT = "ticketing.event.publication.incomplete"
        const val OLDEST_AGE = "ticketing.event.publication.oldest.incomplete.age"

        private val CACHE_TTL = Duration.ofSeconds(10)
        private const val MILLIS_PER_SECOND = 1000.0

        // Flyway가 만든 public 테이블은 jOOQ 코드 생성 대상이 아니므로 식별자로 참조한다
        private val TABLE = DSL.table(DSL.name("public", "event_publication"))
        private val PUBLICATION_DATE = DSL.field(DSL.name("publication_date"), OffsetDateTime::class.java)
        private val COMPLETION_DATE = DSL.field(DSL.name("completion_date"), OffsetDateTime::class.java)
    }
}
