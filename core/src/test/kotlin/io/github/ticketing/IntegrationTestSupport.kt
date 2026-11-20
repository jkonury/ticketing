package io.github.ticketing

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Testcontainers(PostgreSQL, Redis) 위에서 전체 컨텍스트를 띄우는 통합 테스트 베이스.
 * Clock·가짜 PG 등 공용 가변 빈([CommonTestConfiguration])은 매 테스트 전에 초기 상태로 되돌린다.
 */
@SpringBootTest(
    classes = [TicketingApplication::class],
    properties = [
        "ticketing.jwt.secret=test-secret-test-secret-test-secret-0123",
        "ticketing.payment.fake-pg.webhook-secret=test-webhook-secret",
        "ticketing.settlement.chunk-size=1",
        "spring.batch.job.enabled=false",
        // batch 프로필이 끄는 웹 환경을 되살려 MockMvc를 같은 컨텍스트에서 쓴다.
        "spring.main.web-application-type=servlet",
        // batch 프로필이 끄는 Kafka 리스너도 되살린다.
        "spring.kafka.listener.auto-startup=true",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, CommonTestConfiguration::class)
@ActiveProfiles("batch")
abstract class IntegrationTestSupport {
    @BeforeEach
    fun resetSharedTestBeans(@Autowired resetter: SharedTestBeansResetter) {
        resetter.reset()
    }
}

fun publicationIds(jdbc: JdbcTemplate, eventType: Class<*>, field: String, value: Long, listener: String): List<UUID> = jdbc.queryForList(
    "select id from public.event_publication where event_type = ? and serialized_event::jsonb ->> ? = ? and listener_id like ?",
    UUID::class.java,
    eventType.name,
    field,
    value.toString(),
    "%$listener%",
).map { requireNotNull(it) }

fun publicationCompleted(jdbc: JdbcTemplate, id: UUID): Boolean = jdbc.queryForObject(
    "select count(*) from public.event_publication where id = ? and status = 'COMPLETED' and completion_date is not null",
    Int::class.java,
    id,
) == 1
