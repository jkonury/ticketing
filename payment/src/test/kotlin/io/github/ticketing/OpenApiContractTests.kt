package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import ticketing.testing.OpenApiSnapshot

class OpenApiContractTests(private val mockMvcTester: MockMvcTester) : IntegrationTestSupport() {
    @Test
    fun `HTTP API는 커밋된 OpenAPI 계약과 같다`() {
        val result = mockMvcTester.get().uri("/v3/api-docs").accept(MediaType.APPLICATION_JSON).exchange()

        assertThat(result).hasStatus(HttpStatus.OK)
        OpenApiSnapshot.verify("payment", result.response.contentAsByteArray)
    }
}
