package io.github.ticketing.fakepg

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
class ActuatorPrometheusTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `prometheus 지표를 조회할 수 있다`() {
        val response = mockMvc.get("/actuator/prometheus").andReturn().response

        assertTrue(response.status == 200, "status=${response.status}")
        assertTrue(response.contentAsString.contains("application=\"fake-pg\""))
    }
}
