package io.github.ticketing.gateway.sse

import io.github.ticketing.gateway.auth.GatewayAttributes
import io.github.ticketing.gateway.auth.MEMBER_HEADER
import io.github.ticketing.gateway.ratelimit.RateLimitFilter
import io.github.ticketing.gateway.support.writeProblem
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono

// 순서는 티켓 조회 → 회원 키로 한도 → 티켓 소비다. 한도에 걸린 요청이 티켓을 태우지 않아 같은 티켓으로 재시도할 수 있다
@Component
class SseTicketFilter(private val redis: ReactiveStringRedisTemplate, private val rateLimitFilter: RateLimitFilter) : GatewayFilter {
    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val ticket = exchange.request.queryParams.getFirst("ticket")
        if (ticket.isNullOrBlank()) return invalid(exchange)
        val key = "sse:ticket:$ticket"

        return redis.opsForValue().get(key)
            .mapNotNull { it.toLongOrNull() }
            .flatMap { memberId ->
                exchange.attributes[GatewayAttributes.MEMBER_ID] = memberId
                rateLimitFilter.filter(exchange) { limited -> consume(limited, key, memberId, chain) }.thenReturn(true)
            }
            .switchIfEmpty(Mono.defer { invalid(exchange).thenReturn(false) })
            .then()
    }

    private fun consume(exchange: ServerWebExchange, key: String, memberId: Long, chain: GatewayFilterChain): Mono<Void> =
        redis.opsForValue().getAndDelete(key)
            .mapNotNull { it.toLongOrNull() }
            .filter { it == memberId }
            .flatMap {
                val uri = UriComponentsBuilder.fromUri(exchange.request.uri).replaceQueryParam("ticket").build(true).toUri()
                val request = exchange.request.mutate().uri(uri).headers { it.set(MEMBER_HEADER, memberId.toString()) }.build()
                chain.filter(exchange.mutate().request(request).build()).thenReturn(true)
            }
            .switchIfEmpty(Mono.defer { invalid(exchange).thenReturn(false) })
            .then()

    private fun invalid(exchange: ServerWebExchange): Mono<Void> =
        exchange.writeProblem(HttpStatus.UNAUTHORIZED, "SSE_TICKET_INVALID", "유효하지 않은 SSE 티켓입니다.")
}
