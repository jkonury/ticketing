package io.github.ticketing.booking.adapter.required.messaging

import io.github.ticketing.KafkaTopics
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.RefundRequested
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget

/**
 * core가 발행하는 결제 요청 이벤트 2종만 Kafka로 외부화한다. 토픽은 이벤트별, 메시지 키는 `orderId`다(events.md "Kafka 외부화 대상 (M6)").
 * 결제 결과 3종(`PaymentSucceeded`·`PaymentFailed`·`PaymentRefunded`)은 payment 앱이 발행하므로 core는 외부화하지 않고 구독만 한다.
 * 외부화 설정 빈은 하나뿐이라 4단계에서 `BookingConfirmed`·`BookingCancelled`를 추가할 때도 이 구성에 더한다.
 * `contract` 모듈은 Spring에 의존하지 않으므로 `@Externalized` 대신 여기서 라우팅한다.
 */
@Configuration(proxyBeanMethods = false)
class BookingEventExternalizationConfig {
    @Bean
    fun eventExternalizationConfiguration(environment: Environment): EventExternalizationConfiguration {
        // Modulith 외부화 인프라가 이 빈을 BeanPostProcessor 등록 단계에서 만들므로 KafkaTopics 빈 대신 접두어로 직접 만든다
        val topics by lazy { KafkaTopics(environment.getProperty("ticketing.kafka.topic-prefix", "ticketing")) }
        return EventExternalizationConfiguration.externalizing()
            .select { event -> event.javaClass in EXTERNALIZED }
            .routeAll { event -> route(topics, event) }
            .build()
    }

    private fun route(topics: KafkaTopics, event: Any): RoutingTarget = when (event) {
        is PaymentRequested -> to(topics.paymentRequested, event.orderId)
        is RefundRequested -> to(topics.refundRequested, event.orderId)
        else -> error("외부화 대상이 아닌 이벤트: ${event.javaClass.name}")
    }

    private fun to(topic: String, orderId: Long): RoutingTarget = RoutingTarget.forTarget(topic).andKey(orderId.toString())

    private companion object {
        val EXTERNALIZED: Set<Class<*>> = setOf(
            PaymentRequested::class.java,
            RefundRequested::class.java,
        )
    }
}
