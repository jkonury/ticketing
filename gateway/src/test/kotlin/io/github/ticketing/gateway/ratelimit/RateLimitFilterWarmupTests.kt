package io.github.ticketing.gateway.ratelimit

import io.github.ticketing.gateway.auth.GatewayAttributes
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono

internal class RateLimitFilterWarmupTests {
    private val keys = mutableListOf<String>()
    private val limiter = mock(RedisRateLimiter::class.java).also {
        `when`(it.config).thenReturn(mutableMapOf())
    }
    private val route = mock(Route::class.java).also { `when`(it.id).thenReturn("core-bookings-create") }

    private fun filter(enabled: Boolean = true, response: () -> Mono<RateLimiter.Response>) = RateLimitFilter(
        limiter.also {
            `when`(it.isAllowed("core-bookings-create", "warmup:0")).thenAnswer {
                keys += "warmup:0"
                response()
            }
        },
        RateLimitKeyResolver(),
        RateLimitProperties(enabled = enabled),
        SimpleMeterRegistry(),
    )

    private fun run(filter: RateLimitFilter, warmup: Boolean): Boolean {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/bookings"))
        exchange.attributes[GATEWAY_ROUTE_ATTR] = route
        if (warmup) exchange.attributes[GatewayAttributes.STARTUP_WARMUP] = true
        var proceeded = false
        filter.filter(exchange, GatewayFilterChain { Mono.fromRunnable { proceeded = true } }).block()
        return proceeded
    }

    @Test
    fun `예열 요청은 limiter를 실제로 실행하고 거절되어도 통과시킨다`() {
        val filter = filter { Mono.just(RateLimiter.Response(false, emptyMap())) }

        assertThat(run(filter, warmup = true)).isTrue()
        assertThat(keys).containsExactly("warmup:0")
    }

    @Test
    fun `예열 요청은 limiter 오류가 나도 통과한다`() {
        val filter = filter { Mono.error(IllegalStateException("redis down")) }

        assertThat(run(filter, warmup = true)).isTrue()
    }

    @Test
    fun `limiter를 끄면 예열 요청도 limiter를 호출하지 않는다`() {
        val filter = filter(enabled = false) { Mono.just(RateLimiter.Response(true, emptyMap())) }

        assertThat(run(filter, warmup = true)).isTrue()
        assertThat(keys).isEmpty()
    }
}
