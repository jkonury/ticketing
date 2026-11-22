package io.github.ticketing

import io.github.ticketing.payment.PaymentSucceeded
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.kafka.KafkaContainer
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class KafkaRecoveryIntegrationTests {
    @Test
    fun `브로커 장애로 실패한 외부화는 복구 후 결제 앱 재기동에서 결과가 재발행된다`() {
        val namespace = SharedContainers.newNamespace()
        KafkaContainer("apache/kafka:4.3.2").use { kafka ->
            kafka.start()
            verifyRecovery(namespace, kafka)
        }
    }

    private fun verifyRecovery(namespace: Map<String, String>, kafka: KafkaContainer) {
        val args = arguments(namespace, kafka)
        val order = System.nanoTime()
        val event = PaymentSucceeded(1, order, BigDecimal("100"), Instant.parse("2026-11-11T00:00:00Z"))
        val docker = DockerClientFactory.instance().client()
        lateinit var publicationId: UUID
        start(args).use { context ->
            val jdbc = context.getBean(JdbcTemplate::class.java)
            docker.pauseContainerCmd(kafka.containerId).exec()
            try {
                context.getBean(TransactionTemplate::class.java).executeWithoutResult { context.publishEvent(event) }
                await().atMost(Duration.ofSeconds(30)).untilAsserted {
                    val ids = jdbc.queryForList(
                        "select id from public.event_publication where event_type = ? and serialized_event like ? " +
                            "and completion_date is null and status = ?",
                        UUID::class.java,
                        PaymentSucceeded::class.java.name,
                        "%\"orderId\":$order%",
                        "FAILED",
                    )
                    assertThat(ids).hasSize(1)
                    publicationId = requireNotNull(ids.single())
                }
            } finally {
                docker.unpauseContainerCmd(kafka.containerId).exec()
            }
        }
        start(args).use { context ->
            val topic = context.getBean(KafkaTopics::class.java).paymentSucceeded
            val jdbc = context.getBean(JdbcTemplate::class.java)
            await().atMost(Duration.ofSeconds(60)).untilAsserted {
                assertThat(
                    jdbc.queryForObject(
                        "select count(*) from public.event_publication where id = ? and completion_date is not null",
                        Int::class.java,
                        publicationId,
                    ),
                ).isEqualTo(1)
                assertThat(KafkaTestSupport.recordsOf(listOf(kafka.bootstrapServers), topic, order.toString())).hasSize(1)
            }
        }
    }

    private fun arguments(namespace: Map<String, String>, kafka: KafkaContainer): Array<String> = (
        namespace.map { (key, value) -> "--$key=$value" } + listOf(
            "--spring.config.import=",
            "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
            "--spring.kafka.producer.properties.max.block.ms=3000",
            "--spring.kafka.producer.properties.delivery.timeout.ms=5000",
            "--spring.kafka.producer.properties.request.timeout.ms=3000",
            "--spring.kafka.producer.properties.linger.ms=0",
            "--ticketing.startup-warmup.enabled=false",
            "--ticketing.jwt.secret=$TEST_JWT_SECRET",
            "--ticketing.payment.fake-pg.webhook-secret=$TEST_WEBHOOK_SECRET",
            "--server.port=0",
        )
        ).toTypedArray()

    private fun start(args: Array<String>): ConfigurableApplicationContext =
        SpringApplicationBuilder(PaymentApplication::class.java).run(*args)
}
