package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionTemplate
import ticketing.contractfixtures.ContractEventFixtures
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/** 실제 브로커로 나간 메시지의 토픽·키·본문이 계약(골든 JSON, events.md)과 같은지 확인한다. */
class PaymentEventExternalizationTests(
    private val publisher: ApplicationEventPublisher,
    private val transactions: TransactionTemplate,
    private val kafkaProperties: KafkaProperties,
    private val topics: KafkaTopics,
) : IntegrationTestSupport() {
    private val tree = JsonMapper()

    private val expectedTopics = mapOf(
        "PaymentSucceeded" to topics.paymentSucceeded,
        "PaymentFailed" to topics.paymentFailed,
        "PaymentRefunded" to topics.paymentRefunded,
    )

    @Test
    fun `결제 결과 이벤트 3종은 이벤트별 토픽에 orderId 키와 골든 JSON 본문으로 발행된다`() {
        val events = ContractEventFixtures.samples.filter { it.javaClass.simpleName in expectedTopics }
        assertThat(events).hasSize(3)

        transactions.executeWithoutResult { events.forEach(publisher::publishEvent) }

        events.forEach { event ->
            val eventType = event.javaClass.simpleName
            val topic = expectedTopics.getValue(eventType)
            await().atMost(Duration.ofSeconds(15)).untilAsserted {
                val records = KafkaTestSupport.recordsOf(kafkaProperties.bootstrapServers, topic, "101")
                assertThat(records).hasSize(1)
                val record = records.single()
                assertThat(tree.readTree(record.value())).describedAs(eventType)
                    .isEqualTo(tree.readTree(ContractEventFixtures.golden(event)))
                assertThat(record.headers()).noneMatch { it.key().startsWith("__") }
            }
        }
    }
}
