package io.github.ticketing

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// batch 프로필은 이 지표를 끄므로 켜서 검증한다
@TestPropertySource(properties = ["ticketing.event-publication.metrics.enabled=true"])
class EventPublicationMetricsTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val registry: MeterRegistry,
    @Autowired private val clock: MutableClock,
) : IntegrationTestSupport() {

    private val table = DSL.table(DSL.name("public", "event_publication"))

    // 지표가 10초 캐시를 쓰므로 테스트마다 기준 시각을 멀리 떨어뜨려 이전 테스트의 캐시를 무효화한다
    private val base: Instant = BASE.plus(Duration.ofHours(testSequence.incrementAndGet()))

    @BeforeEach
    fun cleanRegistry() {
        dsl.deleteFrom(table).execute()
    }

    @Test
    fun `미완료가 없으면 건수와 경과 시간이 0이다`() {
        clock.now = base

        assertThat(count()).isZero()
        assertThat(oldestAge()).isZero()
    }

    @Test
    fun `완료된 발행은 제외하고 미완료 건수와 최고령 경과 시간을 노출한다`() {
        insert(publishedAt = base.minusSeconds(600), completedAt = base.minusSeconds(590))
        insert(publishedAt = base.minusSeconds(300))
        insert(publishedAt = base.minusSeconds(120))
        clock.now = base

        assertThat(count()).isEqualTo(2.0)
        assertThat(oldestAge()).isEqualTo(300.0)
    }

    @Test
    fun `캐시 TTL이 지나면 다시 조회해 경과 시간이 늘어난다`() {
        insert(publishedAt = base.minusSeconds(60))
        clock.now = base
        assertThat(oldestAge()).isEqualTo(60.0)

        clock.now = base.plusSeconds(30)
        insert(publishedAt = base.minusSeconds(10))

        assertThat(count()).isEqualTo(2.0)
        assertThat(oldestAge()).isEqualTo(90.0)
    }

    private fun count() = registry.get(EventPublicationMetrics.INCOMPLETE_COUNT).gauge().value()

    private fun oldestAge() = registry.get(EventPublicationMetrics.OLDEST_AGE).gauge().value()

    private fun insert(publishedAt: Instant, completedAt: Instant? = null) {
        dsl.insertInto(table)
            .columns(
                DSL.field(DSL.name("id"), UUID::class.java),
                DSL.field(DSL.name("listener_id"), String::class.java),
                DSL.field(DSL.name("event_type"), String::class.java),
                DSL.field(DSL.name("serialized_event"), String::class.java),
                DSL.field(DSL.name("publication_date"), OffsetDateTime::class.java),
                DSL.field(DSL.name("completion_date"), OffsetDateTime::class.java),
            )
            .values(
                UUID.randomUUID(),
                "test.Listener",
                "test.Event",
                "{}",
                publishedAt.atOffset(ZoneOffset.UTC),
                completedAt?.atOffset(ZoneOffset.UTC),
            )
            .execute()
    }

    private companion object {
        val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val testSequence = AtomicLong()
    }
}
