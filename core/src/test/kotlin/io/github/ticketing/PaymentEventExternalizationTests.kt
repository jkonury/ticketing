package io.github.ticketing

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionTemplate
import ticketing.contractfixtures.ContractEventFixtures
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 브로커로 나간 메시지의 토픽·키·본문이 계약(골든 JSON, events.md)과 같은지 확인한다. */
class PaymentEventExternalizationTests(
    private val publisher: ApplicationEventPublisher,
    private val transactions: TransactionTemplate,
    private val kafkaProperties: KafkaProperties,
) : IntegrationTestSupport() {
    private val tree = JsonMapper()

    private val expectedTopics = mapOf(
        "PaymentRequested" to KafkaTopics.PAYMENT_REQUESTED,
        "RefundRequested" to KafkaTopics.REFUND_REQUESTED,
        "PaymentSucceeded" to KafkaTopics.PAYMENT_SUCCEEDED,
        "PaymentFailed" to KafkaTopics.PAYMENT_FAILED,
        "PaymentRefunded" to KafkaTopics.PAYMENT_REFUNDED,
    )

    @Test
    fun `결제 계약 이벤트 5종은 이벤트별 토픽에 orderId 키와 골든 JSON 본문으로 발행된다`() {
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
