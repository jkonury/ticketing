package io.github.ticketing.e2e.support

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

internal enum class SagaTopic(val nameSuffix: String, val consumerGroup: String) {
    PAYMENT_SUCCEEDED("payment.payment-succeeded", "ticketing-booking"),
    REFUND_REQUESTED("payment.refund-requested", "ticketing-payment"),
    PAYMENT_REFUNDED("payment.payment-refunded", "ticketing-booking"),
    BOOKING_CANCELLED("booking.booking-cancelled", "ticketing-settlement"),
    ;

    val topic get() = "ticketing.$nameSuffix"
    val deadLetter get() = "$topic.dlt"
}

internal class SagaKafka(private val orderId: Long, private vararg val contracts: SagaTopic) : AutoCloseable {
    private val config = mapOf("bootstrap.servers" to sagaProperty("kafkaBootstrap"), "default.api.timeout.ms" to "5000")
    private val admin = Admin.create(config)
    private val consumer = KafkaConsumer(
        config + mapOf(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false", ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "none"),
        StringDeserializer(),
        ByteArrayDeserializer(),
    )
    private val sourceStart = snapshot(contracts.map { it.topic })
    private val deadLetterStart = snapshot(contracts.map { it.deadLetter })

    fun brokerResponds(): Boolean = runCatching {
        admin.describeCluster().nodes().get(5, TimeUnit.SECONDS).isNotEmpty()
    }.getOrDefault(false)

    fun published(topic: SagaTopic): Boolean = recordsSince(sourceStart.filterKeys { it.topic() == topic.topic }).isNotEmpty()

    fun consumed(): Boolean = contracts.all { contract ->
        val records = recordsSince(sourceStart.filterKeys { it.topic() == contract.topic })
        if (records.isEmpty()) return@all false

        val committed = admin.listConsumerGroupOffsets(contract.consumerGroup)
            .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
        records.all { (partition, offset) -> (committed[partition]?.offset() ?: -1) > offset }
    }

    fun assertNoDeadLetters() {
        val deadLetters = recordsSince(deadLetterStart)
        assertTrue(deadLetters.isEmpty(), "주문 $orderId 의 DLT 레코드: $deadLetters")
    }

    private fun snapshot(topics: List<String>): Map<TopicPartition, Long> {
        val partitions = admin.describeTopics(topics).allTopicNames().get(5, TimeUnit.SECONDS).values.flatMap { topic ->
            topic.partitions().map { TopicPartition(topic.name(), it.partition()) }
        }
        return endOffsets(partitions)
    }

    private fun endOffsets(partitions: Collection<TopicPartition>): Map<TopicPartition, Long> =
        admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(5, TimeUnit.SECONDS).mapValues { it.value.offset() }

    private fun recordsSince(start: Map<TopicPartition, Long>): List<Pair<TopicPartition, Long>> {
        val end = endOffsets(start.keys)
        val active = start.keys.filter { start.getValue(it) < end.getValue(it) }
        consumer.assign(active)
        active.forEach { consumer.seek(it, start.getValue(it)) }
        val matches = mutableListOf<Pair<TopicPartition, Long>>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (active.any { consumer.position(it, Duration.ofSeconds(2)) < end.getValue(it) }) {
            check(System.nanoTime() < deadline) { "Kafka 오프셋 범위 조회 시간 초과: $active" }
            for (record in consumer.poll(Duration.ofMillis(250))) {
                val partition = TopicPartition(record.topic(), record.partition())
                if (record.key() == orderId.toString() && record.offset() < end.getValue(partition)) {
                    matches += partition to record.offset()
                }
            }
        }
        return matches
    }

    override fun close() {
        try {
            consumer.close(Duration.ofSeconds(2))
        } finally {
            admin.close(Duration.ofSeconds(2))
        }
    }
}
