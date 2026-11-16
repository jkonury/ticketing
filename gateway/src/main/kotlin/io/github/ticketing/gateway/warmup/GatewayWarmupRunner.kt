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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import reactor.util.retry.Retry
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_MAX_SECONDS = 120L
private const val MAX_LOCAL_ITERATIONS = 100000
private const val TOKEN_TTL_SECONDS = 120L
private val ADMISSION_TTL: Duration = Duration.ofMinutes(3)

@ConfigurationProperties("ticketing.startup-warmup")
internal data class GatewayWarmupProperties(
    val enabled: Boolean = false,
    val maxDuration: Duration = Duration.ofSeconds(DEFAULT_MAX_SECONDS),
    val localIterations: Int = 10000,
    val rounds: Int = 15,
    val batchSize: Int = 1000,
    val concurrency: Int = 1000,
) {
    init {
        require(!maxDuration.isNegative && !maxDuration.isZero && maxDuration <= Duration.ofMinutes(2))
        require(localIterations in 0..MAX_LOCAL_ITERATIONS)
        require(rounds in 1..MAX_WARMUP_ROUNDS)
        require(batchSize in 1..MAX_WARMUP_BATCH_SIZE && concurrency in 1..MAX_WARMUP_BATCH_SIZE)
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
    private val credential: StartupWarmupGatewayCredential,
    private val redis: ReactiveStringRedisTemplate,
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

    /**
     * core가 기동 때 만든 일회성 비밀을 서버 역할 JWT로 받고, 실제 입장 토큰 검증 경로를 타도록 회원 -1·회차 1의 입장 토큰을
     * TTL과 함께 만든다. 입장 토큰은 무작위 키라 실제 회원이 쓸 수 없고 회원 ID가 -1이라 실제 회원과 일치하지 않는다.
     */
    private fun credentials(http: WebClient, jwt: String, admissionKey: String): Mono<WarmupCredentials> =
        http.post().uri("/internal/startup-warmup/credential").header(HttpHeaders.AUTHORIZATION, "Bearer $jwt")
            .retrieve().bodyToMono(String::class.java).map { body ->
                mapper.readTree(body).path("key").asString("").also { check(it.isNotEmpty()) }
            }
            .flatMap { key ->
                credential.install(key)
                redis.opsForHash<String, String>().putAll(admissionKey, mapOf("memberId" to "-1", "scheduleId" to "1"))
                    .then(redis.expire(admissionKey, ADMISSION_TTL))
                    .thenReturn(WarmupCredentials(jwt, key, admissionKey.removePrefix("queue:admission:")))
            }

    override fun run(args: ApplicationArguments) {
        val started = System.nanoTime()
        val local = AtomicInteger()
        val traffic = WarmupTrafficCounts()
        val token = warmupToken(jwtProperties.secret, Clock.systemUTC())
        val admissionKey = "queue:admission:warmup-${UUID.randomUUID()}"
        val connections = ConnectionProvider.builder("startup-warmup-generator")
            .maxConnections(properties.concurrency).pendingAcquireMaxCount(MAX_WARMUP_BATCH_SIZE).build()
        val http = WebClient.builder().baseUrl(gateway.coreUri)
            .clientConnector(ReactorClientHttpConnector(HttpClient.create(connections))).build()
        val port =
            environment.getProperty("local.server.port", Int::class.java) ?: environment.getProperty("server.port", Int::class.java, 18000)
        val future = localWarmup(token, local).then(
            http.get().uri("/actuator/health/readiness").retrieve().toBodilessEntity()
                .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofMillis(100))),
        ).then(
            Mono.defer {
                credentials(http, token, admissionKey).flatMap { warmup ->
                    warmupTraffic(http, "http://127.0.0.1:$port", warmup, properties, mapper, traffic)
                }
            },
        ).timeout(properties.maxDuration).toFuture()
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
            connections.dispose()
            // 만료(TTL)가 최종 방어선이다. 기동 스레드를 막지 않도록 비동기로 지운다.
            redis.delete(admissionKey).subscribe({}, {})
            logger.info(
                "startup-warmup outcome={} elapsedMs={} local={} rounds={} remote={} winners={} payments={} details={} retries={} dbStatements={} redisCommands={}",
                outcome,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                local.get(),
                traffic.rounds.get(),
                traffic.completed.get(),
                traffic.winners.get(),
                traffic.payments.get(),
                traffic.details.get(),
                traffic.retries.get(),
                traffic.db.get(),
                traffic.redis.get(),
            )
        }
    }
}
