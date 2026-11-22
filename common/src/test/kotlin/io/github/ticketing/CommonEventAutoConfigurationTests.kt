package io.github.ticketing

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter
import tools.jackson.databind.json.JsonMapper

class CommonEventAutoConfigurationTests {
    private val context = ApplicationContextRunner()
        .withUserConfiguration(ConsumerApplication::class.java)
        .withInitializer { it.beanFactory.conversionService = ApplicationConversionService.getSharedInstance() }

    @Test
    fun `컴포넌트 스캔 없이 Kafka 설정과 지표를 자동 등록한다`() {
        val application = context
            .withBean(KafkaTemplate::class.java, { KafkaTemplate(DefaultKafkaProducerFactory<Any, Any>(emptyMap())) })
            .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
            .withBean(DSLContext::class.java, { DSL.using(SQLDialect.POSTGRES) })
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues("ticketing.kafka.topic-prefix=consumer-app")

        application.run { beans ->
            assertThat(beans).hasSingleBean(KafkaTopics::class.java)
                .hasSingleBean(KafkaAdmin.NewTopics::class.java)
                .hasSingleBean(DefaultErrorHandler::class.java)
                .hasSingleBean(ByteArrayJacksonJsonMessageConverter::class.java)
                .hasSingleBean(EventPublicationMetrics::class.java)
            assertThat(beans.getBean("kafkaTopics", KafkaTopics::class.java).paymentRequested)
                .isEqualTo("consumer-app.payment.payment-requested")
        }
    }

    @Test
    fun `Kafka와 jOOQ가 없는 앱도 공용 빈을 등록한다`() {
        val application = context.withClassLoader(FilteredClassLoader("org.springframework.kafka", "org.apache.kafka", "org.jooq"))

        application.run { beans ->
            assertThat(beans).hasBean("clock")
                .doesNotHaveBean("kafkaTopics")
                .doesNotHaveBean("kafkaTopicDeclarations")
                .doesNotHaveBean("kafkaErrorHandler")
                .doesNotHaveBean("kafkaMessageConverter")
                .doesNotHaveBean("eventPublicationMetrics")
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    private class ConsumerApplication
}
