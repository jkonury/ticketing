package io.github.ticketing.gateway.ratelimit

import io.github.ticketing.gateway.auth.GatewayAttributes
import io.github.ticketing.gateway.testIngressProof
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import java.net.InetSocketAddress

class ClientAddressIngressFilterTests {
    @Test
    fun `같은 IPv6의 표기 차이와 IPv4 매핑은 각각 같은 버킷을 쓴다`() {
        val compressed = resolve("2001:db8::1")
        val full = resolve("2001:0DB8:0:0:0:0:0:1")
        val mapped = resolve("::ffff:192.0.2.1")
        val ipv4 = resolve("192.0.2.1")

        assertThat(compressed).isEqualTo("ip:2001:db8:0:0:0:0:0:1").isEqualTo(full)
        assertThat(mapped).isEqualTo("ip:192.0.2.1").isEqualTo(ipv4)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "localhost", "192.0.2", "192.0.2.256", "192.0.2.1:80", "192.0.2.1, 192.0.2.2", "[::1]", "fe80::1%en0"])
    fun `증명이 있어도 잘못된 주소는 실제 IPv6 연결 주소로 폴백한다`(address: String) {
        val key = resolve(address)

        assertThat(key).isEqualTo("ip:2001:db8:0:0:0:0:0:2")
    }

    @Test
    fun `주소나 증명이 중복되거나 설정을 비워두면 실제 연결 주소를 쓴다`() {
        val duplicateAddresses = resolve("192.0.2.1", "192.0.2.2")
        val duplicateProofs = resolve("192.0.2.1", proofs = arrayOf(testIngressProof, testIngressProof))
        val disabled = resolve("192.0.2.1", configuredProof = "")
        val missingAddress = resolve()

        assertThat(listOf(duplicateAddresses, duplicateProofs, disabled, missingAddress))
            .containsOnly("ip:2001:db8:0:0:0:0:0:2")
    }

    @Test
    fun `검증된 회원 ID가 IP보다 우선한다`() {
        val key = resolve("192.0.2.1", memberId = 42)

        assertThat(key).isEqualTo("member:42")
    }

    private fun resolve(
        vararg addresses: String,
        proofs: Array<String> = arrayOf(testIngressProof),
        configuredProof: String = testIngressProof,
        memberId: Long? = null,
    ): String? {
        val request = MockServerHttpRequest.get("/")
            .remoteAddress(InetSocketAddress("2001:db8::2", 12345))
            .header(CLIENT_IP_HEADER, *addresses)
            .header(INGRESS_PROOF_HEADER, *proofs)
            .build()
        val exchange = MockServerWebExchange.from(request)
        if (memberId != null) exchange.attributes[GatewayAttributes.MEMBER_ID] = memberId
        var forwarded: ServerWebExchange? = null

        ClientAddressIngressFilter(configuredProof).filter(exchange) {
            forwarded = it
            reactor.core.publisher.Mono.empty()
        }.block()

        return RateLimitKeyResolver().resolve(requireNotNull(forwarded)).block()
    }
}
