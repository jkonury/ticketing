package io.github.ticketing.payment.adapter.required.messaging

import io.github.ticketing.KafkaTopics
import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.PaymentSucceeded
import io.github.ticketing.payment.RefundRequested
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget

/**
 * 결제 계약 이벤트 5종만 Kafka로 외부화한다. 토픽은 이벤트별, 메시지 키는 `orderId`다(events.md "Kafka 외부화 대상 (M6)").
 * 외부화 설정 빈은 하나뿐이라 4단계에서 `booking` 이벤트를 추가할 때 이 구성을 어디에 둘지 다시 정한다(루트 패키지는 payment·booking을 참조하면 Modulith 순환이 생긴다).
 * `contract` 모듈은 Spring에 의존하지 않으므로 `@Externalized` 대신 여기서 라우팅한다.
 */
@Configuration(proxyBeanMethods = false)
class PaymentEventExternalizationConfig {
    @Bean
    fun eventExternalizationConfiguration(): EventExternalizationConfiguration = EventExternalizationConfiguration.externalizing()
        .select { event -> event.javaClass in EXTERNALIZED }
        .routeAll { event -> route(event) }
        .build()

    private fun route(event: Any): RoutingTarget = when (event) {
        is PaymentRequested -> to(KafkaTopics.PAYMENT_REQUESTED, event.orderId)
        is RefundRequested -> to(KafkaTopics.REFUND_REQUESTED, event.orderId)
        is PaymentSucceeded -> to(KafkaTopics.PAYMENT_SUCCEEDED, event.orderId)
        is PaymentFailed -> to(KafkaTopics.PAYMENT_FAILED, event.orderId)
        is PaymentRefunded -> to(KafkaTopics.PAYMENT_REFUNDED, event.orderId)
        else -> error("외부화 대상이 아닌 이벤트: ${event.javaClass.name}")
    }

    private fun to(topic: String, orderId: Long): RoutingTarget = RoutingTarget.forTarget(topic).andKey(orderId.toString())

    private companion object {
        val EXTERNALIZED: Set<Class<*>> = setOf(
            PaymentRequested::class.java,
            RefundRequested::class.java,
            PaymentSucceeded::class.java,
            PaymentFailed::class.java,
            PaymentRefunded::class.java,
        )
    }
}
