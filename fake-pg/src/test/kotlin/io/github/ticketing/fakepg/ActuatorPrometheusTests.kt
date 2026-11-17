package io.github.ticketing.fakepg

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
class ActuatorPrometheusTests(@Autowired private val mockMvcTester: MockMvcTester) {

    @Test
    fun `prometheus 지표를 조회할 수 있다`() {
        mockMvcTester.get().uri("/actuator/prometheus")
            .assertThat()
            .hasStatus(HttpStatus.OK)
            .bodyText().contains("application=\"fake-pg\"")
    }
}
