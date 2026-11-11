package io.github.ticketing.gateway

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val TEST_SECRET = "test-secret-test-secret-test-secret-0123"

@AutoConfigureWebTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class GatewayIntegrationTestSupport(private val baseClient: WebTestClient) {
    protected lateinit var client: WebTestClient
    protected val core = TestInfrastructure.core
    protected val realtime = TestInfrastructure.realtime

    @BeforeEach
    fun clearRequests() {
        val id = UUID.randomUUID().hashCode()
        client =
            baseClient.mutate().defaultHeader("X-Forwarded-For", "10.${(id ushr 16) and 255}.${(id ushr 8) and 255}.${id and 255}").build()
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
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("ticketing.gateway.rate-limit.default.replenish-rate") { 1 }
            registry.add("ticketing.gateway.rate-limit.default.burst-capacity") { 2 }
            registry.add("ticketing.gateway.rate-limit.booking-create.replenish-rate") { 1 }
            registry.add("ticketing.gateway.rate-limit.booking-create.burst-capacity") { 2 }
            registry.add("ticketing.gateway.rate-limit.streams.replenish-rate") { 1 }
            registry.add("ticketing.gateway.rate-limit.streams.burst-capacity") { 3 }
            registry.add("ticketing.jwt.secret") { TEST_SECRET }
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
    private val server: DisposableServer = HttpServer.create().host("127.0.0.1").port(0)
        .handle { request, response ->
            request.receive().aggregate().asString().defaultIfEmpty("").flatMap { body ->
                val headers = HttpHeaders()
                request.requestHeaders().forEach { headers.add(it.key, it.value) }
                requests.add(RecordedRequest(request.method().name(), request.path(), URI.create(request.uri()).rawQuery, headers, body))
                response.status(200).sendString(reactor.core.publisher.Mono.just("ok")).then()
            }
        }.bindNow()
    val uri: String get() = "http://127.0.0.1:${server.port()}"

    fun clear() = requests.clear()
}

private object TestInfrastructure {
    val redis = GenericContainer("redis:8.6.2-alpine").withExposedPorts(6379).apply { start() }
    val core = DownstreamStub()
    val realtime = DownstreamStub()
}
