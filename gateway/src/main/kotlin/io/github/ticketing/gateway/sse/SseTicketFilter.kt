package io.github.ticketing.gateway.sse

import io.github.ticketing.gateway.auth.MEMBER_HEADER
import io.github.ticketing.gateway.support.writeProblem
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono

@Component
class SseTicketFilter(private val redis: ReactiveStringRedisTemplate) : GatewayFilter {
    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val ticket = exchange.request.queryParams.getFirst("ticket")
        if (ticket.isNullOrBlank()) return invalid(exchange)

        return redis.opsForValue().getAndDelete("sse:ticket:$ticket")
            .mapNotNull { it.toLongOrNull() }
            .flatMap { memberId ->
                val uri = UriComponentsBuilder.fromUri(exchange.request.uri).replaceQueryParam("ticket").build(true).toUri()
                val request = exchange.request.mutate().uri(uri).headers { it.set(MEMBER_HEADER, memberId.toString()) }.build()
                chain.filter(exchange.mutate().request(request).build()).thenReturn(true)
            }
            .switchIfEmpty(Mono.defer { invalid(exchange).thenReturn(false) })
            .then()
    }

    private fun invalid(exchange: ServerWebExchange): Mono<Void> =
        exchange.writeProblem(HttpStatus.UNAUTHORIZED, "SSE_TICKET_INVALID", "유효하지 않은 SSE 티켓입니다.")
}
