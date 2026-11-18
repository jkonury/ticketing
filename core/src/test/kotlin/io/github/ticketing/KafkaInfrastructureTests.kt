package io.github.ticketing

import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin
import kotlin.test.assertEquals

class KafkaInfrastructureTests(private val kafkaAdmin: KafkaAdmin) : IntegrationTestSupport() {

    @Test
    fun `결제 계약 이벤트 토픽 5종이 파티션 3개로 만들어진다`() {
        val expected = listOf(
            KafkaTopics.PAYMENT_REQUESTED,
            KafkaTopics.REFUND_REQUESTED,
            KafkaTopics.PAYMENT_SUCCEEDED,
            KafkaTopics.PAYMENT_FAILED,
            KafkaTopics.PAYMENT_REFUNDED,
        )

        val partitions = AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
            admin.describeTopics(expected).allTopicNames().get().mapValues { it.value.partitions().size }
        }

        assertEquals(expected.associateWith { KafkaTopics.PARTITIONS }, partitions)
    }
}
