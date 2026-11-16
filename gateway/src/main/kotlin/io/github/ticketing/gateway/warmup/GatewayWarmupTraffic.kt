package io.github.ticketing.gateway.warmup

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.atomic.AtomicInteger

internal const val MAX_WARMUP_ROUNDS = 30
internal const val MAX_WARMUP_BATCH_SIZE = 1000
private const val WINNER_INTERVAL = 20

internal class WarmupTrafficCounts {
    val rounds = AtomicInteger()
    val completed = AtomicInteger()
    val winners = AtomicInteger()
    val db = AtomicInteger()
    val redis = AtomicInteger()
}

/** 예열 HTTP 요청이 실제 운영 경로(`POST /api/v1/bookings`)에서 쓰는 자격이다. */
internal class WarmupCredentials(val jwt: String, val key: String, val admissionToken: String)

internal fun warmupTraffic(
    http: WebClient,
    baseUri: String,
    credentials: WarmupCredentials,
    properties: GatewayWarmupProperties,
    mapper: JsonMapper,
    counts: WarmupTrafficCounts,
): Mono<Void> = Flux.range(0, properties.rounds).concatMap { round ->
    Flux.range(0, properties.batchSize).flatMap({ index ->
        val conflict = index % WINNER_INTERVAL != 0
        http.post().uri("$baseUri/api/v1/bookings")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${credentials.jwt}")
            .header("X-Admission-Token", credentials.admissionToken)
            .header(STARTUP_WARMUP_HEADER, credentials.key)
            .header("Idempotency-Key", if (conflict) WARMUP_CONFLICT_KEY else WARMUP_WINNER_KEY)
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"scheduleId":1,"seatIds":[1]}""")
            .exchangeToMono { response ->
                if (response.statusCode() == if (conflict) HttpStatus.CONFLICT else HttpStatus.CREATED) {
                    val io = response.headers().header(STARTUP_WARMUP_IO_HEADER).firstOrNull()
                    response.bodyToMono(String::class.java).switchIfEmpty(Mono.error(IllegalStateException("빈 예열 응답")))
                        .map { body -> body to requireNotNull(io) { "예열 I/O 헤더 없음" } }
                } else {
                    response.createError<Pair<String, String>>()
                }
            }.doOnNext { (body, io) ->
                val result = mapper.readTree(body)
                check(if (conflict) result.path("code").asString("") == "SEAT_ALREADY_HELD" else !result.path("bookingId").isMissingNode)
                val (db, redis) = parseIo(io)
                counts.db.addAndGet(db)
                counts.redis.addAndGet(redis)
                if (!conflict) counts.winners.incrementAndGet()
                counts.completed.incrementAndGet()
            }
    }, properties.concurrency).then().doOnSuccess {
        counts.rounds.incrementAndGet()
        LoggerFactory.getLogger("startup-warmup").info(
            "startup-warmup round={} requests={} completed={} winners={}",
            round + 1,
            properties.batchSize,
            counts.completed.get(),
            counts.winners.get(),
        )
    }
}.then()

private fun parseIo(header: String): Pair<Int, Int> {
    val values = header.split(';').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
    return values.getValue("db") to values.getValue("redis")
}
