package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.listener.DefaultErrorHandler

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PaymentApplicationTests(private val dsl: DSLContext, private val context: ApplicationContext, private val kafkaAdmin: KafkaAdmin) {

    @Test
    fun `Flyway가 결제 테이블과 이벤트 발행 레지스트리를 생성한다`() {
        val schemas = dsl.meta().schemas.associateBy { it.name }

        assertThat(schemas.getValue("payment").tables.map { it.name })
            .containsExactlyInAnyOrder("payment", "webhook_event", "pg_transaction_snapshot", "reconciliation_mismatch")
        assertThat(schemas.getValue("public").tables.map { it.name }).contains("event_publication")
    }

    @Test
    fun `공용 Kafka 빈과 이벤트 발행 지표가 등록되고 브로커에 토픽이 생성된다`() {
        assertThat(context.getBeanNamesForType(KafkaTopics::class.java)).containsExactly("kafkaTopics")
        assertThat(context.getBeanNamesForType(DefaultErrorHandler::class.java)).containsExactly("kafkaErrorHandler")
        assertThat(context.getBeansOfType(EventPublicationMetrics::class.java)).hasSize(1)

        val topics = context.getBean(KafkaTopics::class.java)
        val descriptions = kafkaAdmin.describeTopics(*topics.all.toTypedArray())

        assertThat(descriptions.keys).containsExactlyInAnyOrderElementsOf(topics.all)
    }
}
