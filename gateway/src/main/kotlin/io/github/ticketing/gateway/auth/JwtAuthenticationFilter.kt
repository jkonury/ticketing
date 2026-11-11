package io.github.ticketing.gateway.auth

import io.github.ticketing.gateway.support.writeProblem
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

object GatewayAttributes {
    const val MEMBER_ID = "gateway.memberId"
}

const val MEMBER_HEADER = "X-Member-Id"

@Component
class JwtAuthenticationFilter(private val decoder: ReactiveJwtDecoder) :
    GlobalFilter,
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request.mutate().headers { it.remove(MEMBER_HEADER) }.build()
        val sanitized = exchange.mutate().request(request).build()
        val authorization = request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        if (authorization == null ||
            !(authorization.equals("Bearer", ignoreCase = true) || authorization.startsWith("Bearer ", ignoreCase = true))
        ) {
            return chain.filter(sanitized)
        }
        return authenticate(sanitized, chain, authorization.substringAfter(' ', "").trim())
    }

    private fun authenticate(sanitized: ServerWebExchange, chain: GatewayFilterChain, token: String): Mono<Void> {
        if (token.isEmpty()) {
            return sanitized.writeProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.")
        }
        return decoder.decode(token)
            .map { jwt -> jwt.subject?.toLongOrNull() ?: throw IllegalArgumentException("유효하지 않은 회원 ID") }
            .onErrorResume { Mono.empty() }
            .flatMap { memberId ->
                sanitized.attributes[GatewayAttributes.MEMBER_ID] = memberId
                chain.filter(sanitized).thenReturn(true)
            }
            .switchIfEmpty(Mono.defer { sanitized.writeProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.").thenReturn(false) })
            .then()
    }
}
