package io.github.ticketing

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import java.time.Duration
import java.util.UUID

/** 테스트가 브로커의 실제 레코드를 확인하는 도구. 소비 그룹에 끼지 않고 파티션을 직접 읽는다. */
object KafkaTestSupport {
    fun awaitConsumed(bootstrapServers: List<String>, topic: String, key: String, group: String, expected: Int) {
        withConsumer(bootstrapServers) { consumer ->
            val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
            consumer.assign(partitions)
            consumer.seekToBeginning(partitions)
            val records = mutableListOf<ConsumerRecord<String, String>>()
            await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(20)).untilAsserted {
                consumer.poll(Duration.ofMillis(100)).forEach { if (it.key() == key) records += it }
                assertThat(records).hasSize(expected)
            }
            Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers)).use { admin ->
                await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(20)).untilAsserted {
                    val offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get()
                    records.forEach { record ->
                        assertThat(offsets[TopicPartition(topic, record.partition())]?.offset()).isGreaterThan(record.offset())
                    }
                }
            }
        }
    }
    fun <T> withConsumer(bootstrapServers: List<String>, block: (KafkaConsumer<String, String>) -> T): T = KafkaConsumer(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "test-reader-${UUID.randomUUID()}",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        StringDeserializer(),
    ).use(block)

    /** [topic]에서 키가 [key]인 레코드를 처음부터 읽어 돌려준다. */
    fun recordsOf(bootstrapServers: List<String>, topic: String, key: String): List<ConsumerRecord<String, String>> =
        withConsumer(bootstrapServers) { consumer ->
            val partitions = consumer.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
            consumer.assign(partitions)
            consumer.seekToBeginning(partitions)
            val end = consumer.endOffsets(partitions)
            val found = mutableListOf<ConsumerRecord<String, String>>()
            while (partitions.any { consumer.position(it) < end.getValue(it) }) {
                consumer.poll(Duration.ofMillis(200)).forEach { if (it.key() == key) found += it }
            }
            found
        }
}
