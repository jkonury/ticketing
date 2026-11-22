package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter
import kotlin.test.assertEquals

class CommonBeansTests(private val beanFactory: ListableBeanFactory) : IntegrationTestSupport() {

    @Test
    fun `ApiExceptionHandler 빈은 스캔과 auto-configuration이 겹쳐도 하나만 등록된다`() {
        val names = beanFactory.getBeanNamesForType(ApiExceptionHandler::class.java).toList()
        assertEquals(1, names.size, "등록된 빈 이름: $names")
    }

    @Test
    fun `core 컴포넌트 스캔이 공용 Kafka 빈을 중복 등록하지 않는다`() {
        assertThat(beanFactory.getBeanNamesForType(KafkaTopics::class.java)).containsExactly("kafkaTopics")
        assertThat(beanFactory.getBeanNamesForType(KafkaAdmin.NewTopics::class.java)).containsExactly("kafkaTopicDeclarations")
        assertThat(beanFactory.getBeanNamesForType(DefaultErrorHandler::class.java)).containsExactly("kafkaErrorHandler")
        assertThat(beanFactory.getBeanNamesForType(ByteArrayJacksonJsonMessageConverter::class.java))
            .containsExactly("kafkaMessageConverter")
    }

    @Test
    fun `batch 프로필은 이벤트 발행 지표를 등록하지 않는다`() {
        assertThat(beanFactory.getBeanNamesForType(EventPublicationMetrics::class.java)).isEmpty()
    }
}
