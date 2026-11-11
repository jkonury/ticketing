package io.github.ticketing.gateway

import io.github.ticketing.gateway.admission.AdmissionTokenFilter
import io.github.ticketing.gateway.admission.AdmissionTokens
import io.github.ticketing.gateway.auth.GatewayAttributes
import io.github.ticketing.gateway.auth.JwtAuthenticationFilter
import io.github.ticketing.gateway.ratelimit.GatewayRateLimiter
import io.github.ticketing.gateway.ratelimit.RateLimitFilter
import io.github.ticketing.gateway.ratelimit.RateLimitKeyResolver
import io.github.ticketing.gateway.ratelimit.RateLimitProperties
import io.github.ticketing.gateway.route.GatewayProperties
import io.github.ticketing.gateway.sse.SseTicketFilter
import io.github.ticketing.gateway.sse.SseTickets
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress

class GatewayFilterUnitTests {
    private class Tokens(var fields: Map<String, String> = mapOf("memberId" to "42", "scheduleId" to "7")) : AdmissionTokens {
        override fun find(token: String): Mono<Map<String, String>> = Mono.just(fields)
    }

    private class Tickets : SseTickets {
        val values = mutableMapOf("one" to "42", "bad" to "invalid")
        override fun consume(ticket: String): Mono<String> = Mono.justOrEmpty(values.remove(ticket))
    }

    @Test
    fun `입장 토큰은 재사용하며 본문을 보존하고 헤더를 제거한다`() {
        val filter = AdmissionTokenFilter(Tokens(), JsonMapper.builder().build(), GatewayProperties())
        val body = "{ \"scheduleId\": 7, \"seatIds\": [4, 5] }"
        repeat(2) {
            val exchange = booking(body, "valid", 42)
            var forwarded = ""
            filter.filter(
                exchange,
                GatewayFilterChain { next ->
                    assertThat(next.request.headers.getFirst("X-Admission-Token")).isNull()
                    DataBufferUtils.join(next.request.body).doOnNext { buffer ->
                        val bytes = ByteArray(buffer.readableByteCount())
                        buffer.read(bytes)
                        forwarded = String(bytes)
                        DataBufferUtils.release(buffer)
                    }.then()
                },
            ).block()
            assertThat(forwarded).isEqualTo(body)
        }
    }

    @Test
    fun `입장 토큰과 본문의 누락 불일치와 크기를 검증한다`() {
        val tokens = Tokens()
        val filter = AdmissionTokenFilter(tokens, JsonMapper.builder().build(), GatewayProperties(bookingBodyMaxBytes = 32))
        val never = GatewayFilterChain { error("요청을 전달하면 안 된다") }
        val missing = booking("{}", null, 42)
        filter.filter(missing, never).block()
        assertThat(missing.response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        listOf(booking("{}", "token", null), booking("{}", "token", 43), booking("{\"scheduleId\":8}", "token", 42)).forEach {
            filter.filter(it, never).block()
            assertThat(it.response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        }
        tokens.fields = emptyMap()
        val unknown = booking("{}", "token", 42)
        filter.filter(unknown, never).block()
        assertThat(unknown.response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        tokens.fields = mapOf("memberId" to "42", "scheduleId" to "7")
        listOf("", "{", "{}", "{\"scheduleId\":\"7\"}", "{\"scheduleId\":7,\"padding\":\"${"x".repeat(32)}\"}").forEach { body ->
            val exchange = booking(body, "token", 42)
            filter.filter(exchange, never).block()
            assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `SSE 티켓은 한 번 소비하고 회원 헤더와 재연결 정보를 전달한다`() {
        val filter = SseTicketFilter(Tickets())
        var calls = 0
        val chain = GatewayFilterChain { next ->
            calls++
            assertThat(next.request.headers.getFirst("X-Member-Id")).isEqualTo("42")
            assertThat(next.request.headers.getFirst("Last-Event-ID")).isEqualTo("456")
            assertThat(next.request.uri.rawQuery).isEqualTo("lastEventId=123")
            Mono.empty()
        }
        fun request(query: String) = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/streams/1$query").header("X-Member-Id", "99").header("Last-Event-ID", "456"),
        )
        filter.filter(request("?ticket=one&lastEventId=123"), chain).block()
        listOf("?ticket=one", "?ticket=bad", "?ticket=missing", "").forEach {
            val exchange = request(it)
            filter.filter(exchange, chain).block()
            assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        }
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `제한 키는 회원을 우선하고 제한 결과와 헤더를 전달한다`() {
        val resolver = RateLimitKeyResolver(RateLimitProperties())
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").remoteAddress(InetSocketAddress("127.0.0.1", 1234)))
        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:127.0.0.1")
        exchange.attributes[GatewayAttributes.MEMBER_ID] = 42L
        assertThat(resolver.resolve(exchange).block()).isEqualTo("member:42")
        exchange.attributes[ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR] =
            Route.async().id("core-api").uri("http://localhost").predicate { true }.build()
        var called = false
        val limiter = object : GatewayRateLimiter {
            override fun isAllowed(route: String, key: String): Mono<RateLimiter.Response> {
                assertThat(route).isEqualTo("core-api")
                assertThat(key).isEqualTo("member:42")
                return Mono.just(RateLimiter.Response(false, mapOf("X-RateLimit-Remaining" to "0")))
            }
        }
        RateLimitFilter(limiter, resolver).filter(
            exchange,
            GatewayFilterChain {
                called = true
                Mono.empty()
            },
        ).block()
        assertThat(called).isFalse()
        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(exchange.response.headers.getFirst("X-RateLimit-Remaining")).isEqualTo("0")
        val allowedExchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").remoteAddress(InetSocketAddress("127.0.0.1", 1234)))
        allowedExchange.attributes[ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR] =
            Route.async().id("core-api").uri("http://localhost").predicate { true }.build()
        val allowed = object : GatewayRateLimiter {
            override fun isAllowed(route: String, key: String): Mono<RateLimiter.Response> =
                Mono.just(RateLimiter.Response(true, emptyMap()))
        }
        RateLimitFilter(allowed, resolver).filter(
            allowedExchange,
            GatewayFilterChain {
                called = true
                Mono.empty()
            },
        ).block()
        assertThat(called).isTrue()
    }

    @Test
    fun `JWT 오류는 거부하고 클라이언트 회원 헤더는 제거한다`() {
        val decoder = ReactiveJwtDecoder { Mono.error(JwtException("invalid")) }
        val filter = JwtAuthenticationFilter(decoder)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("Authorization", "Bearer invalid"))
        filter.filter(exchange, GatewayFilterChain { error("인증 실패 요청을 전달하면 안 된다") }).block()
        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        val anonymous = MockServerWebExchange.from(MockServerHttpRequest.get("/").header("X-Member-Id", "99"))
        filter.filter(
            anonymous,
            GatewayFilterChain { next ->
                assertThat(next.request.headers.getFirst("X-Member-Id")).isNull()
                Mono.empty()
            },
        ).block()
    }

    private fun booking(body: String, token: String?, member: Long?): MockServerWebExchange {
        val request = MockServerHttpRequest.post("/api/v1/bookings")
        if (token != null) request.header("X-Admission-Token", token)
        val exchange = MockServerWebExchange.from(request.body(body))
        if (member != null) exchange.attributes[GatewayAttributes.MEMBER_ID] = member
        return exchange
    }
}
