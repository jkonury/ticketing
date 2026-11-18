package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.events.core.EventSerializer
import ticketing.contractfixtures.ContractEventFixtures
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

/**
 * `event_publication.serialized_event`를 만드는 실제 직렬화기(Modulith [EventSerializer])가
 * contract 모듈의 골든 JSON과 같은 형식을 내는지 확인한다.
 */
class ContractEventPublicationSerializationTests : IntegrationTestSupport() {
    @Autowired
    private lateinit var serializer: EventSerializer

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
}
