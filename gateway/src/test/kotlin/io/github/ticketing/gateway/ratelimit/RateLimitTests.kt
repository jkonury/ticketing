package io.github.ticketing.gateway.ratelimit

import io.github.ticketing.gateway.GatewayIntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.UUID

class RateLimitTests(client: WebTestClient, private val redis: ReactiveStringRedisTemplate) : GatewayIntegrationTestSupport(client) {
    private fun memberId(): Long = UUID.randomUUID().mostSignificantBits.ushr(1)

    private fun admission(memberId: Long): String {
        val ticket = UUID.randomUUID().toString()
        redis.opsForHash<String, String>().putAll("queue:admission:$ticket", mapOf("memberId" to "$memberId", "scheduleId" to "7"))
            .then(redis.expire("queue:admission:$ticket", Duration.ofMinutes(1))).block()
        return ticket
    }

    private fun booking(memberId: Long, admission: String): WebTestClient.ResponseSpec =
        client.post().uri("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
            .headers {
                it.setBearerAuth(token(subject = "$memberId"))
                it.set("X-Admission-Token", admission)
            }
            .bodyValue("""{"scheduleId":7,"seatIds":[1]}""").exchange()

    @Test
    fun `예매 생성은 회원별 한도를 적용한다`() {
        val member = memberId()
        val admission = admission(member)
        val other = memberId()
        val otherAdmission = admission(other)

        repeat(2) { booking(member, admission).expectStatus().isOk.expectHeader().exists("X-RateLimit-Remaining") }
        booking(member, admission).expectStatus().isEqualTo(429).expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")
        booking(other, otherAdmission).expectStatus().isOk

        assertThat(core.requests).hasSize(3)
    }

    private fun claim(memberId: Long?): WebTestClient.ResponseSpec =
        client.post().uri("/api/v1/coupons/9/claims").headers { h -> memberId?.let { h.setBearerAuth(token(subject = "$it")) } }.exchange()

    @Test
    fun `쿠폰 받기는 회원별 coupon-claim 한도를 적용한다`() {
        val member = memberId()
        val other = memberId()

        repeat(2) { claim(member).expectStatus().isOk }
        claim(member).expectStatus().isEqualTo(429).expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")
        claim(other).expectStatus().isOk

        assertThat(core.requests).hasSize(3)
    }

    @Test
    fun `비로그인 쿠폰 받기는 401이다`() {
        claim(null).expectStatus().isUnauthorized

        assertThat(core.requests).isEmpty()
    }

    @Test
    fun `쿠폰 목록 조회는 쿠폰 받기 한도와 별개로 core-api 버킷을 쓴다`() {
        val member = memberId()
        repeat(2) { claim(member).expectStatus().isOk }
        claim(member).expectStatus().isEqualTo(429)

        client.get().uri("/api/v1/coupons").headers { it.setBearerAuth(token(subject = "$member")) }.exchange().expectStatus().isOk
    }

    @Test
    fun `비로그인 요청은 검증된 클라이언트 IP별로 제한한다`() {
        val suffix = UUID.randomUUID().hashCode().ushr(16)
        val first = "198.18.${suffix ushr 8}.${suffix and 255}"
        val other = "198.19.${suffix ushr 8}.${suffix and 255}"
        fun request(ip: String) = client.get().uri("/api/v1/bookings/me").header(CLIENT_IP_HEADER, ip).exchange()

        repeat(2) { request(first).expectStatus().isOk }
        request(first).expectStatus().isEqualTo(429).expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")
        request(other).expectStatus().isOk

        assertThat(core.requests).hasSize(3)
        assertThat(core.requests.map { it.headers.containsHeader(INGRESS_PROOF_HEADER) }).containsOnly(false)
    }

    @Test
    fun `직접 연결은 전달 헤더가 달라도 같은 IP 버킷을 쓴다`() {
        fun request(address: String, proof: String?) = baseClient.get().uri("/api/v1/bookings/me").headers {
            it.set("X-Forwarded-For", address)
            it.set("Forwarded", "for=$address")
            it.set("X-Real-IP", address)
            it.set(CLIENT_IP_HEADER, address)
            if (proof != null) it.set(INGRESS_PROOF_HEADER, proof)
        }.exchange()

        request("192.0.2.1", null).expectStatus().isOk
        request("192.0.2.2", "0".repeat(64)).expectStatus().isOk
        request("192.0.2.3", null).expectStatus().isEqualTo(429).expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")

        assertThat(core.requests).hasSize(2).allSatisfy { request ->
            assertThat(request.headers.headerNames().map(String::lowercase)).doesNotContain(
                "x-forwarded-for",
                "forwarded",
                "x-real-ip",
                CLIENT_IP_HEADER.lowercase(),
                INGRESS_PROOF_HEADER.lowercase(),
            )
        }
    }

    @Test
    fun `스트림 한도는 JWT 대신 소비한 티켓 회원으로 구분한다`() {
        val member = memberId()
        val different = memberId()
        fun request(ticketMember: Long): WebTestClient.ResponseSpec {
            val ticket = UUID.randomUUID().toString()
            redis.opsForValue().set("sse:ticket:$ticket", "$ticketMember", Duration.ofSeconds(30)).block()
            return client.get().uri("/api/v1/streams/1?ticket=$ticket")
                .headers { it.setBearerAuth(token(subject = "$different")) }.exchange()
        }

        repeat(3) { request(member).expectStatus().isOk }
        request(member).expectStatus().isEqualTo(429).expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")
        request(different).expectStatus().isOk

        assertThat(realtime.requests).hasSize(4)
    }

    @Test
    fun `스트림 한도에 걸린 요청은 티켓을 소비하지 않는다`() {
        val member = memberId()
        repeat(3) {
            val ticket = UUID.randomUUID().toString()
            redis.opsForValue().set("sse:ticket:$ticket", "$member", Duration.ofSeconds(30)).block()
            client.get().uri("/api/v1/streams/1?ticket=$ticket").exchange().expectStatus().isOk
        }
        val kept = UUID.randomUUID().toString()
        redis.opsForValue().set("sse:ticket:$kept", "$member", Duration.ofSeconds(30)).block()

        client.get().uri("/api/v1/streams/1?ticket=$kept").exchange()
            .expectStatus().isEqualTo(429).expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")

        assertThat(redis.hasKey("sse:ticket:$kept").block()).isTrue()
        assertThat(realtime.requests).hasSize(3)
    }

    @Test
    fun `무효 입장 토큰도 예매 생성 한도에 먼저 걸린다`() {
        val member = memberId()
        fun invalid() = booking(member, UUID.randomUUID().toString())

        repeat(2) { invalid().expectStatus().isForbidden }
        invalid().expectStatus().isEqualTo(429).expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED")

        assertThat(core.requests).isEmpty()
    }
}
