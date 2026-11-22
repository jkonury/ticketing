package io.github.ticketing

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

internal const val TEST_JWT_SECRET = "test-secret-test-secret-test-secret-0123"
internal const val TEST_WEBHOOK_SECRET = "test-webhook-secret"

/**
 * Testcontainers(PostgreSQL, Kafka) 위에서 전체 컨텍스트를 띄우는 통합 테스트 베이스.
 * Clock·가짜 PG 등 공용 가변 빈([CommonTestConfiguration])은 매 테스트 전에 초기 상태로 되돌린다.
 */
@SpringBootTest(
    classes = [PaymentApplication::class],
    properties = [
        "ticketing.jwt.secret=$TEST_JWT_SECRET",
        "ticketing.payment.fake-pg.webhook-secret=$TEST_WEBHOOK_SECRET",
        "ticketing.startup-warmup.enabled=false",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, CommonTestConfiguration::class)
abstract class IntegrationTestSupport {
    @BeforeEach
    fun resetSharedTestBeans(@Autowired resetter: SharedTestBeansResetter) {
        resetter.reset()
    }
}
