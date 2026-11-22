package io.github.ticketing

import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff
import java.time.Duration

/**
 * 컨슈머 실패 처리(ADR 0043). 지수 백오프로 재시도하고 소진하면 `<토픽>.dlt`로 보내 유실을 막는다.
 * 소비자는 멱등이라 재시도·중복 전달은 안전하다. DLT 적재 수는 `ticketing.kafka.dead.letter` 카운터로 노출한다.
 * 역직렬화 실패처럼 재시도로 낫지 않는 오류는 [DefaultErrorHandler]가 재시도 없이 바로 DLT로 보낸다.
 */
@AutoConfiguration("kafkaConsumerConfig")
@ConditionalOnClass(KafkaOperations::class, MeterRegistry::class)
class KafkaConsumerConfig {
    @Bean
    fun kafkaErrorHandler(
        kafka: KafkaOperations<*, *>,
        topics: KafkaTopics,
        meters: MeterRegistry,
        @Value($$"${ticketing.kafka.consumer.retry.max-retries:3}") maxRetries: Int,
        @Value($$"${ticketing.kafka.consumer.retry.initial-interval:1s}") initialInterval: Duration,
        @Value($$"${ticketing.kafka.consumer.retry.max-interval:10s}") maxInterval: Duration,
    ): DefaultErrorHandler {
        val deadLetters = DeadLetterPublishingRecoverer(kafka) { record, _ -> TopicPartition(topics.deadLetterOf(record.topic()), -1) }
        val recoverer = ConsumerRecordRecoverer { record, exception ->
            meters.counter("ticketing.kafka.dead.letter", "topic", record.topic()).increment()
            deadLetters.accept(record, exception)
        }
        val backOff = ExponentialBackOff(initialInterval.toMillis(), 2.0).apply {
            setMaxInterval(maxInterval.toMillis())
            setMaxAttempts(maxRetries.toLong())
        }
        return DefaultErrorHandler(recoverer, backOff)
    }
}
