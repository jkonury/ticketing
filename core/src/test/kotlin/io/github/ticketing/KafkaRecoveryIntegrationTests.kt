package io.github.ticketing

import io.github.ticketing.payment.PaymentRequested
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
    fun `브로커 장애로 실패한 외부화는 복구 후 재기동에서 재발행되어 처리된다`() {
        val namespace = SharedContainers.newNamespace()
        KafkaContainer("apache/kafka:4.3.2").use { kafka ->
            kafka.start()
            verifyRecovery(namespace, kafka)
        }
    }

    private fun verifyRecovery(namespace: TestNamespace, kafka: KafkaContainer) {
        val args = arguments(namespace, kafka)
        val order = System.nanoTime()
        val event = PaymentRequested(order, 1, BigDecimal("100"), UUID.randomUUID().toString(), Instant.parse("2026-11-11T00:00:00Z"))
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
                        PaymentRequested::class.java.name,
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
            val jdbc = context.getBean(JdbcTemplate::class.java)
            await().atMost(Duration.ofSeconds(60)).untilAsserted {
                assertThat(
                    jdbc.queryForObject(
                        "select count(*) from public.event_publication where id = ? and completion_date is not null",
                        Int::class.java,
                        publicationId,
                    ),
                ).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "select count(*) from payment.payment where order_id = ? and pg_transaction_id = ?",
                        Int::class.java,
                        order,
                        "tx-$order",
                    ),
                )
                    .isEqualTo(1)
            }
        }
    }

    private fun arguments(namespace: TestNamespace, kafka: KafkaContainer): Array<String> = (
        namespace.properties.map { (key, value) -> "--$key=$value" } + listOf(
            "--spring.config.import=",
            "--spring.profiles.active=recovery-test",
            "--spring.profiles.include=",
            "--spring.kafka.bootstrap-servers=${kafka.bootstrapServers}",
            "--spring.kafka.producer.properties.max.block.ms=3000",
            "--spring.kafka.producer.properties.delivery.timeout.ms=5000",
            "--spring.kafka.producer.properties.request.timeout.ms=3000",
            "--spring.kafka.producer.properties.linger.ms=0",
            "--ticketing.startup-warmup.enabled=false",
            "--ticketing.jwt.secret=test-secret-test-secret-test-secret-0123",
            "--ticketing.payment.fake-pg.webhook-secret=test-webhook-secret",
            "--server.port=0",
        )
        ).toTypedArray()

    private fun start(args: Array<String>): ConfigurableApplicationContext =
        SpringApplicationBuilder(TicketingApplication::class.java, CommonTestConfiguration::class.java).run(*args).also { context ->
            assertThat(context.environment.activeProfiles).containsExactly("recovery-test")
            assertThat(context.environment.getProperty("ticketing.startup-warmup.enabled")).isEqualTo("false")
        }
}
