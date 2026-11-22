package io.github.ticketing

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter
import org.springframework.modulith.events.core.EventSerializer
import ticketing.contractfixtures.ContractEventFixtures
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

/**
 * `event_publication.serialized_event`를 만드는 실제 직렬화기(Modulith [EventSerializer])가
 * contract 모듈의 골든 JSON과 같은 형식을 내는지 확인한다. core는 payment 앱이 보낸 결과 이벤트를 Kafka 메시지 변환기로 받으므로
 * 그 수신 경로도 같은 골든 JSON으로 확인한다.
 */
class ContractEventPublicationSerializationTests : IntegrationTestSupport() {
    @Autowired
    private lateinit var serializer: EventSerializer

    @Autowired
    private lateinit var kafkaMessageConverter: ByteArrayJacksonJsonMessageConverter

    private val tree = JsonMapper()

    @Test
    fun `계약 이벤트 7종을 실제 직렬화기로 직렬화하면 골든 JSON과 같다`() {
        ContractEventFixtures.samples.forEach { event ->
            val json = serializer.serialize(event).toString()
            assertEquals(tree.readTree(ContractEventFixtures.golden(event)), tree.readTree(json), event.javaClass.simpleName)
        }
    }

    @Test
    fun `골든 JSON을 실제 직렬화기로 역직렬화하면 원본과 같다`() {
        ContractEventFixtures.samples.forEach { event ->
            assertEquals(event, serializer.deserialize(ContractEventFixtures.golden(event), event.javaClass), event.javaClass.simpleName)
        }
    }

    @Test
    fun `payment 앱이 보낸 결과 이벤트 3종의 골든 JSON을 Kafka 메시지 변환기로 받으면 원본과 같다`() {
        ContractEventFixtures.samples.filter { it.javaClass.simpleName in RESULT_EVENTS }.forEach { event ->
            val record = ConsumerRecord("result", 0, 0L, "101", ContractEventFixtures.golden(event).toByteArray())

            val received = kafkaMessageConverter.toMessage(record, null, null, event.javaClass).payload

            assertEquals(event, received, event.javaClass.simpleName)
        }
    }

    private companion object {
        val RESULT_EVENTS = setOf("PaymentSucceeded", "PaymentFailed", "PaymentRefunded")
    }
}
