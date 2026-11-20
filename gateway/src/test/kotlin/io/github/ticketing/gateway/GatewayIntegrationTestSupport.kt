package io.github.ticketing.gateway

import com.nimbusds.jose.jwk.source.ImmutableSecret
import io.github.ticketing.gateway.ratelimit.CLIENT_IP_HEADER
import io.github.ticketing.gateway.ratelimit.INGRESS_PROOF_HEADER
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val TEST_SECRET = "test-secret-test-secret-test-secret-0123"
internal val testIngressProof = "1234abcd".repeat(8)

@AutoConfigureWebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class GatewayIntegrationTestSupport(protected val baseClient: WebTestClient) {
    @TestBean(name = RedisRateLimiter.REDIS_SCRIPT_NAME, methodName = "fixedTimeRateLimitScript", enforceOverride = true)
    protected lateinit var rateLimitScript: RedisScript<*>

    protected lateinit var client: WebTestClient
    protected val core = TestInfrastructure.core
    protected val realtime = TestInfrastructure.realtime

    @BeforeEach
    fun clearRequests() {
        val id = UUID.randomUUID().hashCode()
        client = baseClient.mutate()
            .defaultHeader(CLIENT_IP_HEADER, "10.${(id ushr 16) and 255}.${(id ushr 8) and 255}.${id and 255}")
            .defaultHeader(INGRESS_PROOF_HEADER, testIngressProof)
            .build()
        core.clear()
        realtime.clear()
    }

    protected fun token(
        subject: String = "42",
        expiresAt: Instant = Instant.now().plusSeconds(300),
        secret: String = TEST_SECRET,
    ): String {
        val encoder = NimbusJwtEncoder(ImmutableSecret(secret.toByteArray()))
        val claims = JwtClaimsSet.builder().subject(subject).issuedAt(Instant.now().minusSeconds(300)).expiresAt(expiresAt).build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }

    companion object {
        @JvmStatic
        fun fixedTimeRateLimitScript(): RedisScript<List<*>> {
            val script = ClassPathResource("META-INF/scripts/request_rate_limiter.lua").getContentAsString(Charsets.UTF_8)
            // Redis 서버의 초 경계와 관계없이 버킷 소진을 검증할 수 있도록 Lua의 시각 인자를 고정한다.
            return RedisScript.of("ARGV[3] = '1'\n$script", List::class.java)
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("ticketing.gateway.rate-limit.default.replenish-rate") { 1 }
            registry.add("ticketing.gateway.rate-limit.default.burst-capacity") { 2 }
            registry.add("ticketing.gateway.rate-limit.booking-create.replenish-rate") { 1 }
            registry.add("ticketing.gateway.rate-limit.booking-create.burst-capacity") { 2 }
            registry.add("ticketing.gateway.rate-limit.coupon-claim.replenish-rate") { 1 }
            registry.add("ticketing.gateway.rate-limit.coupon-claim.burst-capacity") { 2 }
            registry.add("ticketing.gateway.rate-limit.streams.replenish-rate") { 1 }
            registry.add("ticketing.gateway.rate-limit.streams.burst-capacity") { 3 }
            registry.add("ticketing.jwt.secret") { TEST_SECRET }
            registry.add("ticketing.gateway.ingress-proof") { testIngressProof }
            registry.add("ticketing.gateway.core-uri") { TestInfrastructure.core.uri }
            registry.add("ticketing.gateway.realtime-uri") { TestInfrastructure.realtime.uri }
            registry.add("spring.data.redis.host") { TestInfrastructure.redis.host }
            registry.add("spring.data.redis.port") { TestInfrastructure.redis.firstMappedPort }
        }
    }
}

data class RecordedRequest(val method: String, val path: String, val query: String?, val headers: HttpHeaders, val body: String)

class DownstreamStub {
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** 응답을 지연시켜 동시 연결이 겹치게 한다. */
    @Volatile
    var responseDelay: Duration = Duration.ZERO
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()

    /** HTTP/1.1 연결 하나는 한 번에 요청 하나만 처리하므로, 동시에 처리 중인 요청 수가 곧 동시에 열려 있어야 하는 연결 수다. */
    val maxConcurrentConnections: Int get() = maxInFlight.get()

    private val server: DisposableServer = HttpServer.create().host("127.0.0.1").port(0)
        .handle { request, response ->
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            request.receive().aggregate().asString().defaultIfEmpty("").flatMap { body ->
                val headers = HttpHeaders()
                request.requestHeaders().forEach { headers.add(it.key, it.value) }
                requests.add(RecordedRequest(request.method().name(), request.path(), URI.create(request.uri()).rawQuery, headers, body))
                response.status(200).sendString(reactor.core.publisher.Mono.just("ok").delayElement(responseDelay)).then()
                    .doFinally { inFlight.decrementAndGet() }
            }
        }.bindNow()
    val uri: String get() = "http://127.0.0.1:${server.port()}"

    fun clear() {
        requests.clear()
        responseDelay = Duration.ZERO
        maxInFlight.set(inFlight.get())
    }
}

private object TestInfrastructure {
    val redis = GenericContainer("redis:8.6.2-alpine").withExposedPorts(6379).apply { start() }
    val core = DownstreamStub()
    val realtime = DownstreamStub()
}
