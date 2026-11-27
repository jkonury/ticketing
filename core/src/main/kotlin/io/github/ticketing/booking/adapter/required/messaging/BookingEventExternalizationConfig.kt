package io.github.ticketing.booking.adapter.required.messaging

import io.github.ticketing.KafkaTopics
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.RefundRequested
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget

/**
 * core의 결제·환불 요청과 예매 확정·취소를 이벤트별 토픽으로 외부화한다.
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
        is BookingConfirmed -> to(topics.bookingConfirmed, event.bookingId)
        is BookingCancelled -> to(topics.bookingCancelled, event.bookingId)
        else -> error("외부화 대상이 아닌 이벤트: ${event.javaClass.name}")
    }

    private fun to(topic: String, bookingId: Long): RoutingTarget = RoutingTarget.forTarget(topic).andKey(bookingId.toString())

    private companion object {
        val EXTERNALIZED: Set<Class<*>> = setOf(
            PaymentRequested::class.java,
            RefundRequested::class.java,
            BookingConfirmed::class.java,
            BookingCancelled::class.java,
        )
    }
}
