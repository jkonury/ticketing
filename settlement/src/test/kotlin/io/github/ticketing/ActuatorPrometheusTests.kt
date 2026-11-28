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
class ActuatorPrometheusTests(private val mockMvcTester: MockMvcTester, private val dataSource: DataSource) : SettlementWebTestSupport() {

    @Test
    fun `인증 없이 정산 애플리케이션의 prometheus 지표를 조회할 수 있다`() {
        val response = mockMvcTester.get().uri("/actuator/prometheus").exchange()

        response.assertThat()
            .hasStatus(HttpStatus.OK)
            .bodyText()
            .contains("application=\"settlement\"", "hikaricp_connections")
            .doesNotContain("ticketing_event_publication")
    }

    @Test
    fun `일별 정산 배치가 완료되면 prometheus에 실패 값 0이 나온다`() {
        BatchJobMetricsFixture.insertExecution(dataSource, "dailySettlementJob", "COMPLETED", LocalDateTime.of(2026, 1, 1, 0, 0))

        await().atMost(Duration.ofSeconds(3)).untilAsserted {
            mockMvcTester.get().uri("/actuator/prometheus")
                .assertThat()
                .hasStatus(HttpStatus.OK)
                .bodyText()
                .containsPattern("ticketing_batch_job_failed\\{[^}]*job=\"dailySettlementJob\"[^}]*} 0\\.0")
        }
    }
}
