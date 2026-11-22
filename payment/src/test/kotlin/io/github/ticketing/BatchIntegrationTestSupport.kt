package io.github.ticketing

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * 실제 payment-batch와 같은 비웹 `batch` 프로필 컨텍스트로 띄우는 통합 테스트 베이스.
 * Job은 테스트가 직접 실행하므로 `spring.batch.job.enabled`만 끈다.
 */
@SpringBootTest(
    classes = [PaymentApplication::class],
    properties = [
        "ticketing.jwt.secret=$TEST_JWT_SECRET",
        "ticketing.payment.fake-pg.webhook-secret=$TEST_WEBHOOK_SECRET",
        "ticketing.startup-warmup.enabled=true",
        "spring.batch.job.enabled=false",
    ],
)
@Import(TestcontainersConfiguration::class, CommonTestConfiguration::class)
@ActiveProfiles("batch")
abstract class BatchIntegrationTestSupport {
    @BeforeEach
    fun resetSharedTestBeans(@Autowired resetter: SharedTestBeansResetter) {
        resetter.reset()
    }
}
