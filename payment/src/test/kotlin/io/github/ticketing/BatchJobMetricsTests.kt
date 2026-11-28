package io.github.ticketing

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Duration
import java.time.LocalDateTime
import javax.sql.DataSource

@AutoConfigureMetrics
@TestPropertySource(properties = ["ticketing.batch.metrics.refresh-interval=100ms"])
class BatchJobMetricsTests(private val dataSource: DataSource, private val mockMvcTester: MockMvcTester) : IntegrationTestSupport() {
    @Test
    fun `결제 대사 배치가 완료되면 prometheus에 실패 값 0이 나온다`() {
        BatchJobMetricsFixture.insertExecution(dataSource, "paymentReconciliationJob", "COMPLETED", LocalDateTime.of(2026, 1, 1, 0, 0))

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            mockMvcTester.get().uri("/actuator/prometheus")
                .assertThat()
                .hasStatus(HttpStatus.OK)
                .bodyText()
                .containsPattern("ticketing_batch_job_failed\\{[^}]*job=\"paymentReconciliationJob\"[^}]*} 0\\.0")
        }
    }
}
