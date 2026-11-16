package io.github.ticketing.gateway.warmup

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.ticketing.gateway.admission.admissionSchedule
import io.github.ticketing.gateway.auth.JwtProperties
import io.github.ticketing.gateway.auth.bearerToken
import io.github.ticketing.gateway.route.GatewayProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_MAX_SECONDS = 30L
private const val MAX_LOCAL_ITERATIONS = 100000
private const val TOKEN_TTL_SECONDS = 120L

@ConfigurationProperties("ticketing.startup-warmup")
internal data class GatewayWarmupProperties(
    val enabled: Boolean = false,
    val maxDuration: Duration = Duration.ofSeconds(DEFAULT_MAX_SECONDS),
    val localIterations: Int = 10000,
) {
    init {
        require(!maxDuration.isNegative && !maxDuration.isZero && maxDuration <= Duration.ofMinutes(2))
        require(localIterations in 0..MAX_LOCAL_ITERATIONS)
    }
}

internal fun warmupToken(secret: String, clock: Clock): String = SignedJWT(
    JWSHeader(JWSAlgorithm.HS256),
    JWTClaimsSet.Builder().subject("-1").claim("role", "STARTUP_WARMUP")
        .issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(TOKEN_TTL_SECONDS))).build(),
).apply { sign(MACSigner(secret)) }.serialize()

@Component
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
@EnableConfigurationProperties(GatewayWarmupProperties::class)
internal class GatewayWarmupRunner(
    private val properties: GatewayWarmupProperties,
    private val jwtProperties: JwtProperties,
    private val gateway: GatewayProperties,
    private val decoder: ReactiveJwtDecoder,
    private val mapper: JsonMapper,
    private val environment: Environment,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    private fun localWarmup(token: String, completed: AtomicInteger): Mono<Void> {
        val body = """{"scheduleId":1,"seatIds":[1]}""".toByteArray()
        return Flux.range(0, properties.localIterations).concatMap {
            decoder.decode(requireNotNull(bearerToken("Bearer $token"))).doOnNext {
                check(admissionSchedule(mapper, body) == 1L)
                mapper.writeValueAsString(mapper.readTree(body))
                completed.incrementAndGet()
            }
        }.then()
    }

    override fun run(args: ApplicationArguments) {
        val started = System.nanoTime()
        val local = AtomicInteger()
        val remote = AtomicInteger()
        val db = AtomicInteger()
        val redis = AtomicInteger()
        val token = warmupToken(jwtProperties.secret, Clock.systemUTC())
        val http = WebClient.builder().baseUrl(gateway.coreUri).build()
        val port =
            environment.getProperty("local.server.port", Int::class.java) ?: environment.getProperty("server.port", Int::class.java, 18000)
        val future = localWarmup(token, local).then(
            http.get().uri("/actuator/health/readiness").retrieve().toBodilessEntity()
                .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofMillis(100))),
        ).thenMany(
            Flux.range(0, 8).concatMap { index ->
                http.post().uri("http://127.0.0.1:$port/internal/startup-warmup?conflict=${index != 0}")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token").exchangeToMono { response ->
                        if (response.statusCode().value() in setOf(201, 409)) {
                            response.bodyToMono(String::class.java)
                        } else {
                            response.createError<String>()
                        }
                    }
                    .doOnNext { json ->
                        val result = mapper.readTree(json)
                        db.addAndGet(result.path("dbStatements").intValue())
                        redis.addAndGet(result.path("redisCommands").intValue())
                        remote.incrementAndGet()
                    }.delayElement(Duration.ofMillis(50))
            },
        ).then().timeout(properties.maxDuration).toFuture()
        var outcome = "completed"
        try {
            // ApplicationRunner의 기동 스레드만 기다린다. 예열 I/O와 지연은 Reactor에서 비동기로 수행한다.
            future.get(properties.maxDuration.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            outcome = "timeout"
        } catch (exception: ExecutionException) {
            outcome = if (exception.cause is TimeoutException) "timeout" else "failed"
            logger.warn("Gateway 내부 예열 중단: {}", exception.cause?.javaClass?.simpleName)
        } catch (_: InterruptedException) {
            outcome = "interrupted"
            Thread.currentThread().interrupt()
        } finally {
            future.cancel(true)
            logger.info(
                "startup-warmup outcome={} elapsedMs={} local={} remote={} dbStatements={} redisCommands={}",
                outcome,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                local.get(),
                remote.get(),
                db.get(),
                redis.get(),
            )
        }
    }
}
