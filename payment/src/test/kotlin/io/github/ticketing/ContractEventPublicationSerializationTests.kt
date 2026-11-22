package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.modulith.events.core.EventSerializer
import ticketing.contractfixtures.ContractEventFixtures
import tools.jackson.databind.json.JsonMapper

/**
 * `event_publication.serialized_event`를 만드는 실제 직렬화기(Modulith [EventSerializer])가
 * contract 모듈의 골든 JSON과 같은 형식을 내는지 확인한다.
 */
class ContractEventPublicationSerializationTests(private val serializer: EventSerializer) : IntegrationTestSupport() {

    private val tree = JsonMapper()
    private val events = ContractEventFixtures.samples.filter { it.javaClass.packageName == "io.github.ticketing.payment" }

    @Test
    fun `결제 계약 이벤트 5종을 실제 직렬화기로 직렬화하면 골든 JSON과 같다`() {
        events.forEach { event ->
            val json = serializer.serialize(event).toString()
            assertThat(
                tree.readTree(json),
            ).describedAs(event.javaClass.simpleName).isEqualTo(tree.readTree(ContractEventFixtures.golden(event)))
        }
    }

    @Test
    fun `골든 JSON을 실제 직렬화기로 역직렬화하면 원본과 같다`() {
        events.forEach { event ->
            assertThat(serializer.deserialize(ContractEventFixtures.golden(event), event.javaClass))
                .describedAs(event.javaClass.simpleName).isEqualTo(event)
        }
    }
}
