package io.github.ticketing.gateway.warmup

import io.github.ticketing.gateway.auth.GatewayAttributes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

internal class StartupWarmupIngressFilterTests {
    private val credential = StartupWarmupGatewayCredential()
    private val filter = StartupWarmupIngressFilter(
        DefaultListableBeanFactory().apply {
            registerSingleton("credential", credential)
        }.getBeanProvider(StartupWarmupGatewayCredential::class.java),
    )

    private fun pass(header: String?): ServerWebExchange {
        val builder = MockServerHttpRequest.post("/api/v1/bookings")
        header?.let { builder.header(STARTUP_WARMUP_HEADER, it) }
        var forwarded: ServerWebExchange? = null
        filter.filter(
            MockServerWebExchange.from(builder),
            GatewayFilterChain { exchange ->
                forwarded = exchange
                Mono.empty()
            },
        ).block()
        return requireNotNull(forwarded)
    }

    @Test
    fun `자격을 설치하기 전에는 모든 예열 헤더를 제거한다`() {
        val exchange = pass("anything")

        assertThat(exchange.request.headers.getFirst(STARTUP_WARMUP_HEADER)).isNull()
        assertThat(exchange.getAttribute<Boolean>(GatewayAttributes.STARTUP_WARMUP)).isNull()
    }

    @Test
    fun `설치한 자격과 다른 헤더는 제거하고 같은 헤더만 예열 요청으로 표시한다`() {
        credential.install("secret")

        val forged = pass("forged")
        val genuine = pass("secret")

        assertThat(forged.request.headers.getFirst(STARTUP_WARMUP_HEADER)).isNull()
        assertThat(forged.getAttribute<Boolean>(GatewayAttributes.STARTUP_WARMUP)).isNull()
        assertThat(genuine.request.headers.getFirst(STARTUP_WARMUP_HEADER)).isEqualTo("secret")
        assertThat(genuine.getAttribute<Boolean>(GatewayAttributes.STARTUP_WARMUP)).isTrue()
    }

    @Test
    fun `헤더가 없는 요청은 그대로 통과한다`() {
        assertThat(pass(null).getAttribute<Boolean>(GatewayAttributes.STARTUP_WARMUP)).isNull()
    }
}
