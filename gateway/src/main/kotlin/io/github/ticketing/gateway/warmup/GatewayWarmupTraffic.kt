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

internal fun warmupTraffic(
    http: WebClient,
    baseUri: String,
    token: String,
    properties: GatewayWarmupProperties,
    mapper: JsonMapper,
    counts: WarmupTrafficCounts,
): Mono<Void> = Flux.range(0, properties.rounds).concatMap { round ->
    Flux.range(0, properties.batchSize).flatMap({ index ->
        val conflict = index % WINNER_INTERVAL != 0
        http.post().uri("$baseUri/internal/startup-warmup?conflict=$conflict")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header("Idempotency-Key", "startup-warmup")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"scheduleId":1,"seatIds":[1]}""")
            .exchangeToMono { response ->
                if (response.statusCode() == if (conflict) HttpStatus.CONFLICT else HttpStatus.CREATED) {
                    response.bodyToMono(String::class.java).switchIfEmpty(Mono.error(IllegalStateException("빈 예열 응답")))
                } else {
                    response.createError<String>()
                }
            }.doOnNext { json ->
                val result = mapper.readTree(json)
                check(result.path("winner").booleanValue() == !conflict)
                counts.db.addAndGet(result.path("dbStatements").intValue())
                counts.redis.addAndGet(result.path("redisCommands").intValue())
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
