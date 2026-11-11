package io.github.ticketing.realtime.multi

import io.github.ticketing.realtime.FixedClockConfiguration
import io.github.ticketing.realtime.MutableClock
import io.github.ticketing.realtime.RealtimeApplication
import io.github.ticketing.realtime.RealtimeIntegrationTestSupport
import io.github.ticketing.realtime.queue.AdmissionService
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

class SecondInstance : AutoCloseable {
    private val context = SpringApplicationBuilder(RealtimeApplication::class.java, FixedClockConfiguration::class.java).run(
        "--server.port=0",
        "--spring.data.redis.host=${RealtimeIntegrationTestSupport.redisContainer.host}",
        "--spring.data.redis.port=${RealtimeIntegrationTestSupport.redisContainer.firstMappedPort}",
        "--ticketing.realtime.queue.admission-enabled=false",
        "--ticketing.realtime.queue.admission-batch-size=2",
        "--ticketing.realtime.queue.position-poll-interval=50ms",
        "--ticketing.realtime.queue.heartbeat-interval=100ms",
    )
    val client: WebTestClient = WebTestClient.bindToServer()
        .baseUrl("http://localhost:${requireNotNull((context as WebServerApplicationContext).webServer).port}").build()
    val admission: AdmissionService = context.getBean(AdmissionService::class.java)
    val clock: MutableClock = context.getBean(MutableClock::class.java)

    override fun close() = context.close()
}
