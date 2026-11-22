package io.github.ticketing.gateway.admission

import io.github.ticketing.gateway.GatewayIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.UUID

class AdmissionTokenFilterTests(client: WebTestClient, private val redis: ReactiveStringRedisTemplate) :
    GatewayIntegrationTestSupport(client) {
    private fun admission(member: String = "42", schedule: String = "7"): String {
        val token = UUID.randomUUID().toString()
        val key = "queue:admission:$token"
        redis.opsForHash<String, String>().putAll(key, mapOf("memberId" to member, "scheduleId" to schedule))
            .then(redis.expire(key, Duration.ofMinutes(1))).block()
        return token
    }

    // 한도가 검증 필터보다 먼저 적용되므로 테스트마다 다른 회원을 써서 버킷을 공유하지 않는다
    private fun newMember(): String = UUID.randomUUID().mostSignificantBits.ushr(1).toString()

    private fun post(
        body: String,
        admission: String? = null,
        authenticated: Boolean = true,
        member: String = "42",
    ): WebTestClient.ResponseSpec = client.post().uri("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
        .headers {
            if (authenticated) it.setBearerAuth(token(subject = member))
            if (admission != null) it.add("X-Admission-Token", admission)
        }.bodyValue(body).exchange()

    @Test
    fun `일치하는 토큰은 재사용할 수 있고 본문을 그대로 전달한다`() {
        val member = newMember()
        val admission = admission(member)
        val body = "{ \"seatIds\": [ 4, 5 ], \"scheduleId\": 7 }"

        repeat(2) { post(body, admission, member = member).expectStatus().isOk }

        assertThat(core.requests).hasSize(2)
        core.requests.forEach {
            assertThat(it.body).isEqualTo(body)
            assertThat(it.headers.getFirst("X-Admission-Token")).isNull()
        }
    }

    @Test
    fun `토큰 누락과 무효 또는 불일치 시 거부한다`() {
        val body = """{"scheduleId":7,"seatIds":[1]}"""
        val scheduleMember = newMember()
        // (입장 토큰, 요청 JWT 회원): 무효 토큰, 회원 불일치, 같은 회원의 회차 불일치
        val invalid = listOf(
            UUID.randomUUID().toString() to newMember(),
            admission(member = "43") to newMember(),
            admission(member = scheduleMember, schedule = "8") to scheduleMember,
        )

        post(body, member = newMember()).expectStatus().isForbidden.expectBody().jsonPath("$.code").isEqualTo("ADMISSION_TOKEN_REQUIRED")
        invalid.forEach { (admission, member) ->
            post(body, admission, member = member)
                .expectStatus().isForbidden.expectBody().jsonPath("$.code").isEqualTo("ADMISSION_TOKEN_INVALID")
        }

        assertThat(core.requests).isEmpty()
    }

    @Test
    fun `유효하지 않거나 큰 본문은 거부한다`() {
        val bodies = listOf("{", "{}", """{"scheduleId":"7"}""", """{"scheduleId":7,"padding":"${"x".repeat(16384)}"}""")

        bodies.forEach {
            val member = newMember()
            post(it, admission(member), member = member).expectStatus().isBadRequest
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST_BODY")
        }

        assertThat(core.requests).isEmpty()
    }

    @Test
    fun `인증 없이 생성할 수 없다`() {
        post("{}", authenticated = false).expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")

        assertThat(core.requests).isEmpty()
    }

    @Test
    fun `결제와 조회에는 입장 토큰을 요구하지 않는다`() {
        client.post().uri("/api/v1/bookings/1/payments").exchange().expectStatus().isOk
        client.get().uri("/api/v1/bookings/me").exchange().expectStatus().isOk

        assertThat(core.requests).hasSize(2)
    }
}
