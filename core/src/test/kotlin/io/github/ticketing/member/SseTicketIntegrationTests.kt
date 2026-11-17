package io.github.ticketing.member

import io.github.ticketing.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class SseTicketIntegrationTests(private val mockMvcTester: MockMvcTester, private val redis: StringRedisTemplate) :
    IntegrationTestSupport() {
    private val json = JsonMapper.builder().build()

    private fun credentials(role: String): Pair<String, String> {
        val email = "sse-${UUID.randomUUID()}@example.com"
        val signup = json.writeValueAsString(mapOf("email" to email, "password" to "password123", "name" to "회원", "role" to role))
        val member = mockMvcTester.post().uri("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content(signup).exchange()
        assertThat(member).hasStatus(HttpStatus.CREATED)
        val login = json.writeValueAsString(mapOf("email" to email, "password" to "password123"))
        val tokens = mockMvcTester.post().uri("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON).content(login).exchange()
        assertThat(tokens).hasStatus(HttpStatus.OK)
        return json.readTree(member.response.contentAsString)["id"].asString() to
            json.readTree(tokens.response.contentAsString)["accessToken"].asString()
    }

    private fun issue(token: String): tools.jackson.databind.JsonNode {
        val result: MvcTestResult = mockMvcTester.post().uri("/api/v1/sse-tickets").header("Authorization", "Bearer $token").exchange()
        assertThat(result).hasStatus(HttpStatus.CREATED)
        return json.readTree(result.response.contentAsString)
    }

    @Test
    fun `인증된 회원은 유효 기간이 있는 일회용 티켓을 발급받는다`() {
        val (memberId, token) = credentials("USER")

        val response = issue(token)

        val ticket = response["ticket"].asString()
        assertThat(ticket).matches("[A-Za-z0-9_-]{43}")
        assertThat(Instant.parse(response["expiresAt"].asString())).isNotNull()
        assertThat(redis.opsForValue().get("sse:ticket:$ticket")).isEqualTo(memberId)
        assertThat(redis.getExpire("sse:ticket:$ticket", TimeUnit.MILLISECONDS)).isBetween(1L, 30_000L)
    }

    @Test
    fun `인증 없이 티켓을 발급할 수 없다`() {
        mockMvcTester.post().uri("/api/v1/sse-tickets").assertThat().hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `주최자가 두 번 발급하면 서로 다른 티켓을 받는다`() {
        val (_, token) = credentials("ORGANIZER")

        val first = issue(token)["ticket"].asString()
        val second = issue(token)["ticket"].asString()

        assertThat(first).isNotEqualTo(second)
    }
}
