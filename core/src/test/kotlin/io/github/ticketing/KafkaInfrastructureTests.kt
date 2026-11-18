package io.github.ticketing

import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin
import kotlin.test.assertEquals

class KafkaInfrastructureTests(private val kafkaAdmin: KafkaAdmin, private val topics: KafkaTopics) : IntegrationTestSupport() {

    @Test
    fun `결제 계약 이벤트 토픽 5종이 파티션 3개로 만들어진다`() {
        val expected = topics.all

        val partitions = AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
            admin.describeTopics(expected).allTopicNames().get().mapValues { it.value.partitions().size }
        }

        assertEquals(expected.associateWith { KafkaTopics.PARTITIONS }, partitions)
    }

    @Test
    fun `토픽마다 재시도를 소진한 레코드를 받는 DLT가 만들어진다`() {
        val deadLetters = topics.all.map(topics::deadLetterOf)

        val names = AdminClient.create(kafkaAdmin.configurationProperties).use { admin -> admin.listTopics().names().get() }

        assertEquals(deadLetters.toSet(), names.intersect(deadLetters.toSet()))
    }
}
