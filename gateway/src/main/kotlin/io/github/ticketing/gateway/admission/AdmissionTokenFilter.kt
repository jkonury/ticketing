package io.github.ticketing.gateway.admission

import io.github.ticketing.gateway.auth.GatewayAttributes
import io.github.ticketing.gateway.route.GatewayProperties
import io.github.ticketing.gateway.support.timedStage
import io.github.ticketing.gateway.support.writeProblem
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

private const val ADMISSION_HEADER = "X-Admission-Token"

@Component
class AdmissionTokenFilter(
    private val redis: ReactiveStringRedisTemplate,
    private val jsonMapper: JsonMapper,
    private val properties: GatewayProperties,
    private val registry: MeterRegistry,
) : GatewayFilter {
    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val token = exchange.request.headers.getFirst(ADMISSION_HEADER)?.takeIf { it.isNotBlank() }
            ?: return exchange.writeProblem(HttpStatus.FORBIDDEN, "ADMISSION_TOKEN_REQUIRED", "입장 토큰이 필요합니다.")
        val memberId = exchange.getAttribute<Long>(GatewayAttributes.MEMBER_ID)
        return redis.opsForHash<String, String>().entries("queue:admission:$token").collectMap({ it.key }, { it.value })
            .timedStage(registry, "admission-redis")
            .flatMap { fields ->
                if (fields["memberId"]?.toLongOrNull() != memberId || memberId == null || fields["scheduleId"]?.toLongOrNull() == null) {
                    invalidToken(exchange)
                } else {
                    validateBody(exchange, chain, fields.getValue("scheduleId").toLong())
                }
            }
    }

    private fun validateBody(exchange: ServerWebExchange, chain: GatewayFilterChain, scheduleId: Long): Mono<Void> =
        DataBufferUtils.join(exchange.request.body, properties.bookingBodyMaxBytes)
            .timedStage(registry, "admission-body")
            .flatMap { buffer ->
                val limitedRequest = object : ServerHttpRequestDecorator(exchange.request) {
                    override fun getBody(): Flux<DataBuffer> = Flux.just(buffer)
                }
                val limitedExchange = exchange.mutate().request(limitedRequest).build()
                ServerWebExchangeUtils.cacheRequestBody(limitedExchange) { cachedRequest ->
                    val cached = limitedExchange.getAttribute<DataBuffer>(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR)
                    val bytes = cached?.toByteBuffer()?.let { byteBuffer -> ByteArray(byteBuffer.remaining()).also(byteBuffer::get) }
                    val requestedSchedule = bytes?.let(::parseSchedule)
                    when {
                        requestedSchedule == null -> invalidBody(exchange)

                        requestedSchedule != scheduleId -> invalidToken(exchange)

                        else -> {
                            val request = cachedRequest.mutate().headers { it.remove(ADMISSION_HEADER) }.build()
                            chain.filter(limitedExchange.mutate().request(request).build())
                        }
                    }.thenReturn(true)
                }
            }.switchIfEmpty(Mono.defer { invalidBody(exchange).thenReturn(false) })
            .onErrorResume(DataBufferLimitException::class.java) { invalidBody(exchange).thenReturn(false) }
            .then()

    private fun parseSchedule(bytes: ByteArray): Long? = admissionSchedule(jsonMapper, bytes)

    private fun invalidToken(exchange: ServerWebExchange): Mono<Void> =
        exchange.writeProblem(HttpStatus.FORBIDDEN, "ADMISSION_TOKEN_INVALID", "유효하지 않은 입장 토큰입니다.")

    private fun invalidBody(exchange: ServerWebExchange): Mono<Void> =
        exchange.writeProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "유효하지 않은 요청 본문입니다.")
}

internal fun admissionSchedule(jsonMapper: JsonMapper, bytes: ByteArray): Long? = try {
    val value = jsonMapper.readTree(bytes).get("scheduleId")
    if (value != null && value.isIntegralNumber && value.canConvertToLong()) value.longValue() else null
} catch (_: tools.jackson.core.JacksonException) {
    null
}
