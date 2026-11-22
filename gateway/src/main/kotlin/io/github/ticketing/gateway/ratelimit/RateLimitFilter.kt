package io.github.ticketing.gateway.ratelimit

import io.github.ticketing.gateway.auth.GatewayAttributes
import io.github.ticketing.gateway.support.timedStage
import io.github.ticketing.gateway.support.writeProblem
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_REPLENISH_RATE = 20
private const val DEFAULT_BURST_CAPACITY = 40L
private const val BOOKING_BURST_CAPACITY = 5L
private const val COUPON_CLAIM_REPLENISH_RATE = 1
private const val COUPON_CLAIM_BURST_CAPACITY = 3L
private const val STREAM_REPLENISH_RATE = 5
private const val STREAM_BURST_CAPACITY = 10L
private const val WARMUP_KEY_POOL = 1024

@ConfigurationProperties("ticketing.gateway.rate-limit")
data class RateLimitProperties(
    val default: Limit = Limit(DEFAULT_REPLENISH_RATE, DEFAULT_BURST_CAPACITY),
    val bookingCreate: Limit = Limit(2, BOOKING_BURST_CAPACITY),
    // 임시 기본값이다. 수치는 coupon-drop 부하 측정 후 정한다
    val couponClaim: Limit = Limit(COUPON_CLAIM_REPLENISH_RATE, COUPON_CLAIM_BURST_CAPACITY),
    val streams: Limit = Limit(STREAM_REPLENISH_RATE, STREAM_BURST_CAPACITY),
    // 부하 테스트에서 rate limiter Redis 호출의 몫을 가르는 대조용이다. 운영 기본값은 켜짐
    val enabled: Boolean = true,
) {
    data class Limit(val replenishRate: Int, val burstCapacity: Long)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfiguration

@Component
class RateLimitKeyResolver : KeyResolver {
    override fun resolve(exchange: ServerWebExchange): Mono<String> {
        val memberId = exchange.getAttribute<Long>(GatewayAttributes.MEMBER_ID)
        if (memberId != null) return Mono.just("member:$memberId")
        val address = exchange.getAttribute<String>(VERIFIED_CLIENT_IP)
            ?: exchange.request.remoteAddress?.address?.let { InetAddress.getByAddress(it.address).hostAddress }
        return Mono.just("ip:${address ?: "unknown"}")
    }
}

@Component
class RateLimitFilter(
    private val limiter: RedisRateLimiter,
    private val keyResolver: RateLimitKeyResolver,
    properties: RateLimitProperties,
    private val registry: MeterRegistry,
) : GatewayFilter {
    private val enabled = properties.enabled
    private val warmupSequence = AtomicLong()

    init {
        mapOf(
            "core-api" to properties.default,
            "payment-api" to properties.default,
            "realtime-queues" to properties.default,
            "core-bookings-create" to properties.bookingCreate,
            "core-coupon-claims" to properties.couponClaim,
            "realtime-streams" to properties.streams,
        ).forEach { (route, limit) ->
            limiter.config[route] = RedisRateLimiter.Config()
                .setReplenishRate(limit.replenishRate).setBurstCapacity(limit.burstCapacity)
        }
    }

    override fun filter(exchange: ServerWebExchange, chain: org.springframework.cloud.gateway.filter.GatewayFilterChain): Mono<Void> =
        when {
            !enabled -> chain.filter(exchange)
            exchange.getAttribute<Boolean>(GatewayAttributes.STARTUP_WARMUP) == true -> filterWarmup(exchange, chain)
            else -> filterLimited(exchange, chain)
        }

    // 예열 요청도 실제 isAllowed(Redis 스크립트·응답 매핑)를 실행해 JIT가 그 경로를 익히게 한다. 키를 회원 -1 하나로 두면
    // 버킷이 소진되므로 예열 전용 키 풀을 돌려 쓰고 결과는 무시한다. 측정 지표(stage 타이머)를 오염시키지 않도록 기록하지 않는다. 429는 만들지 않으며 키는 limiter TTL로 사라진다
    private fun filterWarmup(exchange: ServerWebExchange, chain: org.springframework.cloud.gateway.filter.GatewayFilterChain): Mono<Void> {
        val route = exchange.getRequiredAttribute<org.springframework.cloud.gateway.route.Route>(GATEWAY_ROUTE_ATTR)
        val key = "warmup:${warmupSequence.getAndIncrement() % WARMUP_KEY_POOL}"
        return limiter.isAllowed(route.id, key)
            .onErrorResume { Mono.empty() }
            .then(Mono.defer { chain.filter(exchange) })
    }

    private fun filterLimited(exchange: ServerWebExchange, chain: org.springframework.cloud.gateway.filter.GatewayFilterChain): Mono<Void> =
        keyResolver.resolve(exchange).flatMap { key ->
            val route = exchange.getRequiredAttribute<org.springframework.cloud.gateway.route.Route>(GATEWAY_ROUTE_ATTR)
            limiter.isAllowed(route.id, key).timedStage(registry, "ratelimit-redis", route.id).flatMap { result ->
                result.headers.forEach { (name, value) -> exchange.response.headers.add(name, value) }
                if (result.isAllowed) {
                    Mono.defer { chain.filter(exchange) }.thenReturn(true).timedStage(registry, "proxy", route.id).then()
                } else {
                    exchange.writeProblem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청 한도를 초과했습니다.")
                }
            }
        }
}
