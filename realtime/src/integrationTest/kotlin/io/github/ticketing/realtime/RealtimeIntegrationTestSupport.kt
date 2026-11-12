package io.github.ticketing.realtime

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.GenericContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

@AutoConfigureWebTestClient
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ticketing.realtime.queue.position-poll-interval=50ms", "ticketing.realtime.queue.heartbeat-interval=50ms"],
)
@Import(FixedClockConfiguration::class)
abstract class RealtimeIntegrationTestSupport(protected val client: WebTestClient, protected val redis: ReactiveStringRedisTemplate) {
    protected suspend fun prepareSchedule(scheduleId: Long) {
        redis.opsForHash<String, String>().put("booking:seats:{$scheduleId}", "1", "AVAILABLE").awaitSingle()
    }

    protected fun clientFor(memberId: Long): WebTestClient = client.mutate().defaultHeader("X-Member-Id", memberId.toString()).build()

    companion object {
        val now: Instant = Instant.parse("2026-11-11T00:00:00Z")
        val redisContainer = GenericContainer("redis:8.6.2-alpine").withExposedPorts(6379).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { redisContainer.host }
            registry.add("spring.data.redis.port") { redisContainer.firstMappedPort }
            registry.add("ticketing.realtime.queue.admission-enabled") { false }
        }
    }
}

@TestConfiguration
class FixedClockConfiguration {
    @Bean
    @Primary
    fun fixedClock(): MutableClock = MutableClock()
}

class MutableClock : Clock() {
    private val current = AtomicReference(RealtimeIntegrationTestSupport.now)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)

    override fun instant(): Instant = current.get()

    fun advance(duration: Duration) {
        current.updateAndGet { it.plus(duration) }
    }

    fun reset() {
        current.set(RealtimeIntegrationTestSupport.now)
    }
}
