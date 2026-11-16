package io.github.ticketing.gateway.warmup

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.atomic.AtomicInteger

internal const val MAX_WARMUP_ROUNDS = 30
internal const val MAX_WARMUP_BATCH_SIZE = 1000
private const val WINNER_INTERVAL = 20
private const val PAYMENT_INTERVAL = 20
private const val PAYMENT_OFFSET = 5
private const val DETAIL_INTERVAL = 11
private const val DETAIL_OFFSET = 3
private const val MAX_SEATS = 4
private const val SEAT_SPREAD = 3
private const val MAX_RETRIES = 2L

internal class WarmupTrafficCounts {
    val rounds = AtomicInteger()
    val completed = AtomicInteger()
    val winners = AtomicInteger()
    val payments = AtomicInteger()
    val details = AtomicInteger()
    val retries = AtomicInteger()
    val db = AtomicInteger()
    val redis = AtomicInteger()
}

/** 예열 HTTP 요청이 실제 운영 경로에서 쓰는 자격이다. */
internal class WarmupCredentials(val jwt: String, val key: String, val admissionToken: String)

/** 한 회차 안에서 섞어 보내는 요청 종류다. 비율은 hot-seats 예매 구간의 예매 생성·결제·상세 조회 호출 비율을 따른다. */
internal enum class WarmupKind { WINNER, CONFLICT, PAYMENT, DETAIL }

internal fun warmupKind(index: Int): WarmupKind = when {
    index % PAYMENT_INTERVAL == PAYMENT_OFFSET -> WarmupKind.PAYMENT
    index % WINNER_INTERVAL == 0 -> WarmupKind.WINNER
    index % DETAIL_INTERVAL == DETAIL_OFFSET -> WarmupKind.DETAIL
    else -> WarmupKind.CONFLICT
}

// 실제 요청은 1~4장을 고르므로 예열 템플릿도 좌석 수를 섞는다. 좌석은 항상 1부터 연속이다.
internal fun warmupSeatCount(index: Int): Int = 1 + (index / SEAT_SPREAD + index / WINNER_INTERVAL) % MAX_SEATS

private fun warmupRequest(
    http: WebClient,
    baseUri: String,
    credentials: WarmupCredentials,
    index: Int,
): Pair<WarmupKind, WebClient.RequestHeadersSpec<*>> {
    val kind = warmupKind(index)
    val authorized: (WebClient.RequestHeadersSpec<*>) -> WebClient.RequestHeadersSpec<*> = { spec ->
        spec.header(HttpHeaders.AUTHORIZATION, "Bearer ${credentials.jwt}").header(STARTUP_WARMUP_HEADER, credentials.key)
    }
    val spec = when (kind) {
        WarmupKind.WINNER, WarmupKind.CONFLICT -> {
            val seats = (1..warmupSeatCount(index)).joinToString(",")
            authorized(
                http.post().uri("$baseUri/api/v1/bookings")
                    .header("X-Admission-Token", credentials.admissionToken)
                    .header("Idempotency-Key", if (kind == WarmupKind.CONFLICT) WARMUP_CONFLICT_KEY else WARMUP_WINNER_KEY)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"scheduleId":1,"seatIds":[$seats]}"""),
            )
        }

        WarmupKind.PAYMENT -> authorized(
            http.post().uri("$baseUri/api/v1/bookings/$WARMUP_BOOKING_ID/payments").header("Idempotency-Key", WARMUP_PAYMENT_KEY),
        )

        WarmupKind.DETAIL -> authorized(http.get().uri("$baseUri/api/v1/bookings/$WARMUP_BOOKING_ID"))
    }
    return kind to spec
}

private fun expectedStatus(kind: WarmupKind): HttpStatus = when (kind) {
    WarmupKind.WINNER -> HttpStatus.CREATED
    WarmupKind.CONFLICT -> HttpStatus.CONFLICT
    WarmupKind.PAYMENT -> HttpStatus.ACCEPTED
    WarmupKind.DETAIL -> HttpStatus.OK
}

private fun verify(kind: WarmupKind, result: JsonNode) {
    when (kind) {
        WarmupKind.CONFLICT -> check(result.path("code").asString("") == "SEAT_ALREADY_HELD")
        WarmupKind.WINNER -> check(!result.path("bookingId").isMissingNode)
        WarmupKind.PAYMENT -> check(!result.path("bookingId").isMissingNode && !result.path("status").isMissingNode)
        WarmupKind.DETAIL -> check(result.path("status").asString("") == "PENDING")
    }
}

internal fun warmupTraffic(
    http: WebClient,
    baseUri: String,
    credentials: WarmupCredentials,
    properties: GatewayWarmupProperties,
    mapper: JsonMapper,
    counts: WarmupTrafficCounts,
): Mono<Void> = Flux.range(0, properties.rounds).concatMap { round ->
    Flux.range(0, properties.batchSize).flatMap({ index ->
        val (kind, request) = warmupRequest(http, baseUri, credentials, index)
        request.exchangeToMono { response ->
            if (response.statusCode() == expectedStatus(kind)) {
                val io = response.headers().header(STARTUP_WARMUP_IO_HEADER).firstOrNull()
                response.bodyToMono(String::class.java).switchIfEmpty(Mono.error(IllegalStateException("빈 예열 응답")))
                    .map { body -> body to requireNotNull(io) { "예열 I/O 헤더 없음" } }
            } else {
                response.createError<Pair<String, String>>()
            }
        }.retryWhen(
            // 예열 요청은 격리되어 다시 보내도 데이터가 남지 않는다. 코어 accept 큐가 넘쳐 연결이 끊긴 경우(첫 회차의 동시 연결 폭주)만 재시도한다.
            Retry.max(MAX_RETRIES).filter { failure ->
                failure is WebClientRequestException || (failure is WebClientResponseException && failure.statusCode.is5xxServerError)
            }.doBeforeRetry { counts.retries.incrementAndGet() },
        ).doOnNext { (body, io) ->
            verify(kind, mapper.readTree(body))
            val (db, redis) = parseIo(io)
            counts.db.addAndGet(db)
            counts.redis.addAndGet(redis)
            when (kind) {
                WarmupKind.WINNER -> counts.winners.incrementAndGet()
                WarmupKind.PAYMENT -> counts.payments.incrementAndGet()
                WarmupKind.DETAIL -> counts.details.incrementAndGet()
                WarmupKind.CONFLICT -> Unit
            }
            counts.completed.incrementAndGet()
        }
    }, properties.concurrency).then().doOnSuccess {
        counts.rounds.incrementAndGet()
        LoggerFactory.getLogger("startup-warmup").info(
            "startup-warmup round={} requests={} completed={} winners={} payments={} details={} retries={}",
            round + 1,
            properties.batchSize,
            counts.completed.get(),
            counts.winners.get(),
            counts.payments.get(),
            counts.details.get(),
            counts.retries.get(),
        )
    }
}.then()

private fun parseIo(header: String): Pair<Int, Int> {
    val values = header.split(';').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
    return values.getValue("db") to values.getValue("redis")
}
