package io.github.ticketing

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import ticketing.contractfixtures.ContractEventFixtures
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import kotlin.test.assertEquals

/**
 * 계약 이벤트의 JSON 형식을 골든 파일(`contract-events` 디렉터리의 JSON)로 고정한다.
 * 이 테스트가 깨지면 `event_publication`에 남은 행과 다른 프로세스의 구독자가 깨질 수 있다는 뜻이므로,
 * 골든 파일을 고치기 전에 `docs/architecture/events.md`의 하위 호환 규칙을 확인한다.
 */
class ContractEventSerializationTests {

    // Spring Boot가 만드는 JsonMapper(Modulith EventSerializer가 사용)와 같은 설정: 시각은 ISO-8601 문자열, 모르는 필드는 무시
    private val mapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    @ParameterizedTest
    @MethodSource("samples")
    fun `직렬화 결과가 골든 JSON과 같다`(event: Any) {
        assertEquals(mapper.readTree(ContractEventFixtures.golden(event)), mapper.readTree(mapper.writeValueAsString(event)))
    }

    @ParameterizedTest
    @MethodSource("samples")
    fun `골든 JSON을 역직렬화하면 원본과 같다`(event: Any) {
        assertEquals(event, mapper.readValue(ContractEventFixtures.golden(event), event.javaClass))
    }

    @ParameterizedTest
    @MethodSource("samples")
    fun `모르는 필드가 추가된 JSON도 읽는다`(event: Any) {
        val extended = (
            mapper.readTree(
                ContractEventFixtures.golden(event),
            ) as tools.jackson.databind.node.ObjectNode
            ).put("addedLater", "x")
        assertEquals(event, mapper.readValue(mapper.writeValueAsString(extended), event.javaClass))
    }

    @Test
    fun `모든 계약 이벤트 7종에 샘플이 있다`() {
        assertEquals(7, ContractEventFixtures.samples.map { it.javaClass }.toSet().size)
    }

    companion object {
        @JvmStatic
        fun samples(): List<Any> = ContractEventFixtures.samples
    }
}
