package io.github.ticketing.gateway.ratelimit

import io.github.ticketing.gateway.auth.GatewayAttributes
import io.github.ticketing.gateway.support.writeProblem
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

private const val DEFAULT_REPLENISH_RATE = 20
private const val DEFAULT_BURST_CAPACITY = 40L
private const val BOOKING_BURST_CAPACITY = 5L
private const val STREAM_REPLENISH_RATE = 5
private const val STREAM_BURST_CAPACITY = 10L

@ConfigurationProperties("ticketing.gateway.rate-limit")
data class RateLimitProperties(
    val default: Limit = Limit(DEFAULT_REPLENISH_RATE, DEFAULT_BURST_CAPACITY),
    val bookingCreate: Limit = Limit(2, BOOKING_BURST_CAPACITY),
    val streams: Limit = Limit(STREAM_REPLENISH_RATE, STREAM_BURST_CAPACITY),
    val trustedProxies: Int = 1,
) {
    data class Limit(val replenishRate: Int, val burstCapacity: Long)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfiguration

@Component
class RateLimitKeyResolver(properties: RateLimitProperties) : KeyResolver {
    private val addressResolver = XForwardedRemoteAddressResolver.maxTrustedIndex(properties.trustedProxies)

    override fun resolve(exchange: ServerWebExchange): Mono<String> {
        val memberId = exchange.getAttribute<Long>(GatewayAttributes.MEMBER_ID)
        if (memberId != null) return Mono.just("member:$memberId")
        val address = addressResolver.resolve(exchange)
        return Mono.just("ip:${address.address?.hostAddress ?: address.hostString}")
    }
}

@Component
class RateLimitFilter(
    private val limiter: RedisRateLimiter,
    private val keyResolver: RateLimitKeyResolver,
    properties: RateLimitProperties,
) : GatewayFilter {
    init {
        mapOf(
            "core-api" to properties.default,
            "realtime-queues" to properties.default,
            "core-bookings-create" to properties.bookingCreate,
            "realtime-streams" to properties.streams,
        ).forEach { (route, limit) ->
            limiter.config[route] = RedisRateLimiter.Config()
                .setReplenishRate(limit.replenishRate).setBurstCapacity(limit.burstCapacity)
        }
    }

    override fun filter(exchange: ServerWebExchange, chain: org.springframework.cloud.gateway.filter.GatewayFilterChain): Mono<Void> =
        keyResolver.resolve(exchange).flatMap { key ->
            val route = exchange.getRequiredAttribute<org.springframework.cloud.gateway.route.Route>(GATEWAY_ROUTE_ATTR)
            limiter.isAllowed(route.id, key).flatMap { result ->
                result.headers.forEach { (name, value) -> exchange.response.headers.add(name, value) }
                if (result.isAllowed) {
                    chain.filter(exchange)
                } else {
                    exchange.writeProblem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청 한도를 초과했습니다.")
                }
            }
        }
}
