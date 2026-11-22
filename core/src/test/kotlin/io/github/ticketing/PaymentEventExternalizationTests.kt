package io.github.ticketing

import io.github.ticketing.payment.RefundRequested
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ticketing.contractfixtures.ContractEventFixtures
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** core가 실제 브로커로 내보내는 메시지의 토픽·키·본문이 계약(골든 JSON, events.md)과 같은지 확인한다. */
class PaymentEventExternalizationTests(
    private val publisher: ApplicationEventPublisher,
    private val transactions: TransactionTemplate,
    private val kafkaProperties: KafkaProperties,
    private val topics: KafkaTopics,
    private val jdbc: JdbcTemplate,
) : IntegrationTestSupport() {
    private val tree = JsonMapper()

    // core가 발행하는 결제 계약 이벤트는 요청 2종뿐이다. 결과 3종은 payment 앱이 발행한다
    private val expectedTopics = mapOf(
        "PaymentRequested" to topics.paymentRequested,
        "RefundRequested" to topics.refundRequested,
    )

    @Test
    fun `결제 요청 계약 이벤트 2종은 이벤트별 토픽에 orderId 키와 골든 JSON 본문으로 발행된다`() {
        val events = ContractEventFixtures.samples.filter { it.javaClass.simpleName in expectedTopics }
        val consumer = consumer()
        val partitions = expectedTopics.values.flatMap { topic -> (0 until KafkaTopics.PARTITIONS).map { TopicPartition(topic, it) } }
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        partitions.forEach { consumer.position(it) } // seekToEnd는 지연 실행이라 발행 전에 위치를 확정한다

        transactions.executeWithoutResult { events.forEach(publisher::publishEvent) }

        val received = mutableMapOf<String, org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>()
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (received.size < events.size && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach { record ->
                if (record.key() == "101") received[record.topic()] = record
            }
        }
        consumer.close()

        assertEquals(expectedTopics.values.toSet(), received.keys)
        events.forEach { event ->
            val record = received.getValue(expectedTopics.getValue(event.javaClass.simpleName))
            assertEquals("101", record.key(), event.javaClass.simpleName)
            assertEquals(tree.readTree(ContractEventFixtures.golden(event)), tree.readTree(record.value()), event.javaClass.simpleName)
            // 타입 헤더가 있으면 분리 후 패키지가 달라질 때 소비자가 깨진다(ADR 0043)
            assertNull(record.headers().lastHeader("__TypeId__"), event.javaClass.simpleName)
            assertTrue(record.headers().none { it.key().startsWith("__") }, event.javaClass.simpleName)
        }
    }

    @Test
    fun `결제 결과 이벤트 3종은 core가 외부화하지 않는다`() {
        val results = ContractEventFixtures.samples.filter { it.javaClass.simpleName in RESULT_EVENTS }
        val barrier = RefundRequested(102, "USER_CANCELLED", Instant.parse("2026-01-02T03:04:05Z"))
        val consumer = consumer()
        val partitions = (0 until KafkaTopics.PARTITIONS).map { TopicPartition(topics.refundRequested, it) }
        consumer.assign(partitions)
        consumer.seekToEnd(partitions)
        partitions.forEach { consumer.position(it) }

        transactions.executeWithoutResult {
            results.forEach(publisher::publishEvent)
            publisher.publishEvent(barrier)
        }

        // 결과 이벤트와 같은 트랜잭션에서 발행한 요청 이벤트가 브로커에 도착한 뒤에도 결과 토픽에는 아무것도 없어야 한다
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        var delivered = false
        while (!delivered && System.nanoTime() < deadline) {
            delivered = consumer.poll(Duration.ofMillis(500)).any { it.key() == "102" }
        }
        consumer.close()

        assertTrue(delivered)
        listOf(topics.paymentSucceeded, topics.paymentFailed, topics.paymentRefunded).forEach { topic ->
            assertEquals(emptyList(), KafkaTestSupport.recordsOf(kafkaProperties.bootstrapServers, topic, "101"), topic)
        }
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from public.event_publication where event_type in (?, ?, ?)",
                Int::class.java,
                *results.map { it.javaClass.name }.toTypedArray(),
            ),
        )
    }

    private companion object {
        val RESULT_EVENTS = setOf("PaymentSucceeded", "PaymentFailed", "PaymentRefunded")
    }

    private fun consumer() = KafkaConsumer(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaProperties.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "externalization-test-${UUID.randomUUID()}",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        StringDeserializer(),
    )
}
