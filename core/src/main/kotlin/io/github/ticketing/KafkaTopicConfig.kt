package io.github.ticketing

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * 결제 계약 이벤트 5종의 토픽. 이름·파티션 규칙은 docs/architecture/events.md "Kafka 외부화 대상 (M6)"를 따른다.
 * 브로커 자동 생성에 맡기면 파티션 수가 브로커 기본값에 묶이므로 명시적으로 만든다.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicConfig {
    @Bean
    fun paymentRequestedTopic(): NewTopic = topic(KafkaTopics.PAYMENT_REQUESTED)

    @Bean
    fun refundRequestedTopic(): NewTopic = topic(KafkaTopics.REFUND_REQUESTED)

    @Bean
    fun paymentSucceededTopic(): NewTopic = topic(KafkaTopics.PAYMENT_SUCCEEDED)

    @Bean
    fun paymentFailedTopic(): NewTopic = topic(KafkaTopics.PAYMENT_FAILED)

    @Bean
    fun paymentRefundedTopic(): NewTopic = topic(KafkaTopics.PAYMENT_REFUNDED)

    private fun topic(name: String): NewTopic = TopicBuilder.name(name).partitions(KafkaTopics.PARTITIONS).replicas(1).build()
}

object KafkaTopics {
    const val PARTITIONS = 3
    const val PAYMENT_REQUESTED = "ticketing.payment.payment-requested"
    const val REFUND_REQUESTED = "ticketing.payment.refund-requested"
    const val PAYMENT_SUCCEEDED = "ticketing.payment.payment-succeeded"
    const val PAYMENT_FAILED = "ticketing.payment.payment-failed"
    const val PAYMENT_REFUNDED = "ticketing.payment.payment-refunded"
}
