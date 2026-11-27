package io.github.ticketing

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin

/**
 * 프로세스 간 계약 이벤트의 토픽 이름. 규칙은 docs/architecture/events.md "Kafka 외부화 대상 (M6)"를 따른다.
 * 접두어(`ticketing.kafka.topic-prefix`, 기본 `ticketing`)는 한 브로커를 여러 환경이 나눠 쓸 때 충돌을 막는다.
 * 테스트는 컨텍스트마다 접두어를 달리해 컨텍스트 사이에 이벤트가 섞이지 않게 한다. 리스너는 `#{@kafkaTopics.paymentRequested}`로 참조한다.
 */
class KafkaTopics(prefix: String) {
    val paymentRequested = "$prefix.payment.payment-requested"
    val refundRequested = "$prefix.payment.refund-requested"
    val paymentSucceeded = "$prefix.payment.payment-succeeded"
    val paymentFailed = "$prefix.payment.payment-failed"
    val paymentRefunded = "$prefix.payment.payment-refunded"
    val bookingConfirmed = "$prefix.booking.booking-confirmed"
    val bookingCancelled = "$prefix.booking.booking-cancelled"

    val all = listOf(
        paymentRequested,
        refundRequested,
        paymentSucceeded,
        paymentFailed,
        paymentRefunded,
        bookingConfirmed,
        bookingCancelled,
    )

    /** 재시도를 소진한 레코드가 가는 토픽. 원본과 같은 키로 보존한다. */
    fun deadLetterOf(topic: String) = "$topic.dlt"

    companion object {
        const val PARTITIONS = 3
    }
}

@AutoConfiguration("kafkaTopicConfig")
@ConditionalOnClass(KafkaAdmin::class)
class KafkaTopicConfig {
    @Bean
    fun kafkaTopics(@Value($$"${ticketing.kafka.topic-prefix:ticketing}") prefix: String): KafkaTopics = KafkaTopics(prefix)

    /** 브로커 자동 생성에 맡기면 파티션 수가 브로커 기본값에 묶이므로 명시적으로 만든다. */
    @Bean
    fun kafkaTopicDeclarations(topics: KafkaTopics): KafkaAdmin.NewTopics {
        val sources = topics.all.map { TopicBuilder.name(it).partitions(KafkaTopics.PARTITIONS).replicas(1).build() }
        val deadLetters = topics.all.map { TopicBuilder.name(topics.deadLetterOf(it)).partitions(1).replicas(1).build() }
        return KafkaAdmin.NewTopics(*(sources + deadLetters).toTypedArray<NewTopic>())
    }
}
