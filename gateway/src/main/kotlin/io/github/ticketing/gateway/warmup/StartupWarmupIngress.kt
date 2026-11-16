package io.github.ticketing.gateway.warmup

import io.github.ticketing.gateway.auth.GatewayAttributes
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

internal const val STARTUP_WARMUP_HEADER = "X-Startup-Warmup-Key"
internal const val STARTUP_WARMUP_IO_HEADER = "X-Startup-Warmup-Io"
internal const val WARMUP_WINNER_KEY = "startup-warmup:winner"
internal const val WARMUP_CONFLICT_KEY = "startup-warmup:conflict"
internal const val WARMUP_PAYMENT_KEY = "startup-warmup:payment"
internal const val WARMUP_BOOKING_ID = -1L
private val WINDOW: Duration = Duration.ofMinutes(3)

/** core가 기동 때 발급한 예열 자격을 메모리에만 보관한다. 로그·저장소에 남기지 않으며 설치 후 [WINDOW] 동안만 유효하다. */
@Component
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class StartupWarmupGatewayCredential {
    private class Installed(val key: ByteArray, val expiresAtNanos: Long)

    private val installed = AtomicReference<Installed?>()

    fun install(key: String) {
        installed.set(Installed(key.toByteArray(), System.nanoTime() + WINDOW.toNanos()))
    }

    fun matches(candidate: String): Boolean {
        val current = installed.get() ?: return false
        return System.nanoTime() - current.expiresAtNanos < 0 && MessageDigest.isEqual(candidate.toByteArray(), current.key)
    }
}

/**
 * 외부에서 들어온 예열 자격 헤더는 Gateway가 보유한 자격과 일치하지 않으면 제거한다. 예열을 켜지 않았거나 예열 기간이 끝났으면
 * 항상 제거한다. 일치한 요청만 속성으로 표시해 Gateway 자신이 보낸 예열 요청으로 취급한다.
 */
@Component
internal class StartupWarmupIngressFilter(private val credential: ObjectProvider<StartupWarmupGatewayCredential>) :
    GlobalFilter,
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val header = exchange.request.headers.getFirst(STARTUP_WARMUP_HEADER)
        val genuine = header != null && credential.ifAvailable?.matches(header) == true
        if (genuine) exchange.attributes[GatewayAttributes.STARTUP_WARMUP] = true
        val sanitized = if (header == null || genuine) {
            exchange
        } else {
            exchange.mutate().request(exchange.request.mutate().headers { it.remove(STARTUP_WARMUP_HEADER) }.build()).build()
        }
        return chain.filter(sanitized)
    }
}
