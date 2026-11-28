package io.github.ticketing

import io.github.ticketing.payment.adapter.provided.event.PaymentRequestListener
import io.github.ticketing.payment.adapter.provided.warmup.PaymentWarmupRunner
import io.github.ticketing.payment.adapter.provided.warmup.PaymentWarmupWork
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.repository.JobRepository
import org.springframework.boot.web.server.WebServer
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.context.WebApplicationContext

class PaymentBatchContextTests(
    private val context: ApplicationContext,
    private val environment: Environment,
    private val repository: JobRepository,
    private val jdbc: JdbcTemplate,
) : BatchIntegrationTestSupport() {
    @Test
    fun `batch 컨텍스트는 웹 없이 뜨고 웹 전용 빈이 없다`() {
        assertThat(context).isNotInstanceOf(WebApplicationContext::class.java)
        assertThat(context.getBeansOfType(SecurityFilterChain::class.java)).isEmpty()
        assertThat(context.getBeansOfType(WebServer::class.java)).isEmpty()
    }

    @Test
    fun `예열 설정이 켜져 있어도 batch 컨텍스트에는 예열 러너와 작업이 없다`() {
        assertThat(environment.getProperty("ticketing.startup-warmup.enabled")).isEqualTo("true")
        assertThat(context.getBeansOfType(PaymentWarmupRunner::class.java)).isEmpty()
        assertThat(context.getBeansOfType(PaymentWarmupWork::class.java)).isEmpty()
    }

    @Test
    fun `Kafka 리스너는 등록되어도 기동하지 않고 이벤트 발행 지표 빈이 없다`() {
        assertThat((context as ConfigurableApplicationContext).environment.getProperty("spring.kafka.listener.auto-startup"))
            .isEqualTo("false")
        val registry = context.getBean(KafkaListenerEndpointRegistry::class.java)
        assertThat(registry.listenerContainers).isNotEmpty().allSatisfy { assertThat(it.isRunning).isFalse() }
        assertThat(context.getBeansOfType(PaymentRequestListener::class.java)).isNotEmpty()
        assertThat(context.getBeansOfType(EventPublicationMetrics::class.java)).isEmpty()
        assertThat(context.getBeansOfType(BatchJobMetrics::class.java)).isEmpty()
    }

    @Test
    fun `배치 메타데이터는 batch 스키마에 저장된다`() {
        val name = "test-${java.util.UUID.randomUUID()}"
        val instance = repository.createJobInstance(name, org.springframework.batch.core.job.parameters.JobParameters())

        val count = jdbc.queryForObject(
            "SELECT count(*) FROM batch.batch_job_instance WHERE job_instance_id = ?",
            Long::class.java,
            instance.instanceId,
        )
        assertThat(count).isEqualTo(1L)
    }
}
