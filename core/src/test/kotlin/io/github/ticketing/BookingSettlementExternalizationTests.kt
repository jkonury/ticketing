package io.github.ticketing

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import ticketing.contractfixtures.ContractEventFixtures
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

class BookingSettlementExternalizationTests(
    private val publisher: ApplicationEventPublisher,
    private val transactions: TransactionTemplate,
    private val kafkaProperties: KafkaProperties,
    @Value("\${ticketing.kafka.topic-prefix}") private val topicPrefix: String,
) : IntegrationTestSupport() {
    @Test
    fun `예매 확정과 취소는 각각의 토픽에 예매 키와 골든 JSON으로 발행된다`() {
        val confirmed = ContractEventFixtures.samples.filterIsInstance<BookingConfirmed>().single()
        val cancelled = ContractEventFixtures.samples.filterIsInstance<BookingCancelled>().single()
        val expectedEvents = mapOf(
            "$topicPrefix.booking.booking-confirmed" to confirmed,
            "$topicPrefix.booking.booking-cancelled" to cancelled,
        )
        val mapper = JsonMapper()
        val partitions = expectedEvents.keys.flatMap { topic -> (0 until 3).map { TopicPartition(topic, it) } }

        KafkaTestSupport.withConsumer(kafkaProperties.bootstrapServers) { consumer ->
            consumer.assign(partitions)
            consumer.seekToEnd(partitions)
            partitions.forEach { consumer.position(it, Duration.ofSeconds(5)) }

            transactions.executeWithoutResult { expectedEvents.values.forEach(publisher::publishEvent) }

            val received = mutableSetOf<String>()
            await().atMost(Duration.ofSeconds(10)).untilAsserted {
                consumer.poll(Duration.ofMillis(200)).filter { it.key() == confirmed.bookingId.toString() }.forEach { record ->
                    val event = expectedEvents.getValue(record.topic())
                    assertThat(mapper.readTree(record.value())).isEqualTo(mapper.readTree(ContractEventFixtures.golden(event)))
                    assertThat(record.headers()).noneMatch { it.key().startsWith("__") }
                    received += record.topic()
                }
                assertThat(received).isEqualTo(expectedEvents.keys)
            }
        }
    }

    @Test
    fun `브로커가 멈춰 발행하지 못한 예매 취소는 복구 후 재기동에서 발행된다`() {
        val cancelled = ContractEventFixtures.samples.filterIsInstance<BookingCancelled>().single()
        val namespace = SharedContainers.newNamespace()

        TestKafkaContainer().use { kafka ->
            kafka.start()
            val arguments = KafkaRecoveryTestSupport.arguments(namespace, kafka) + "--spring.kafka.listener.auto-startup=false"
            val docker = DockerClientFactory.instance().client()
            lateinit var publicationId: UUID
            KafkaRecoveryTestSupport.start(arguments).use { context ->
                val jdbc = context.getBean(JdbcTemplate::class.java)
                docker.pauseContainerCmd(kafka.containerId).exec()
                try {
                    context.getBean(TransactionTemplate::class.java).executeWithoutResult { context.publishEvent(cancelled) }

                    await().atMost(Duration.ofSeconds(30)).untilAsserted {
                        val failed = jdbc.queryForList(
                            "select id from public.event_publication where event_type = ? and status = 'FAILED' and completion_date is null",
                            UUID::class.java,
                            BookingCancelled::class.java.name,
                        )
                        assertThat(failed).hasSize(1)
                        publicationId = requireNotNull(failed.single())
                    }
                } finally {
                    docker.unpauseContainerCmd(kafka.containerId).exec()
                }
            }

            KafkaRecoveryTestSupport.start(arguments).use { context ->
                val jdbc = context.getBean(JdbcTemplate::class.java)
                await().atMost(Duration.ofSeconds(60)).untilAsserted {
                    assertThat(
                        jdbc.queryForObject(
                            "select count(*) from public.event_publication where id = ? and completion_date is not null",
                            Int::class.java,
                            publicationId,
                        ),
                    ).isEqualTo(1)
                }

                val topic = context.getBean(KafkaTopics::class.java).bookingCancelled
                val records = KafkaTestSupport.recordsOf(listOf(kafka.bootstrapServers), topic, cancelled.bookingId.toString())
                assertThat(records).hasSize(1)
                val mapper = JsonMapper()
                assertThat(mapper.readTree(records.single().value())).isEqualTo(mapper.readTree(ContractEventFixtures.golden(cancelled)))
            }
        }
    }
}
