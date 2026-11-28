package io.github.ticketing

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.LocalDateTime
import java.time.ZoneId

@AutoConfiguration(
    afterName = [
        "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
    ],
)
@ConditionalOnClass(DSLContext::class, MeterRegistry::class)
@ConditionalOnBean(DSLContext::class, MeterRegistry::class)
@Conditional(BatchJobMetricsAutoConfiguration.JobsConfigured::class)
@EnableScheduling
class BatchJobMetricsAutoConfiguration {
    @Bean
    fun batchJobMetrics(dsl: DSLContext, registry: MeterRegistry, environment: Environment): BatchJobMetrics =
        BatchJobMetrics(dsl, registry, configuredJobs(environment))

    internal class JobsConfigured : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            configuredJobs(context.environment).isNotEmpty()
    }

    private companion object {
        fun configuredJobs(environment: Environment): List<String> =
            Binder.get(environment).bind("ticketing.batch.metrics.jobs", Bindable.listOf(String::class.java)).orElse(null).orEmpty()
    }
}

class BatchJobMetrics(private val dsl: DSLContext, registry: MeterRegistry, private val jobNames: List<String>) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val failed = MultiGauge.builder("ticketing.batch.job.failed")
        .description("최근 완료된 Batch Job 실행이 실패 상태이면 1")
        .register(registry)
    private val lastSuccess = MultiGauge.builder("ticketing.batch.job.last.success")
        .description("최근 성공한 Batch Job 실행의 종료 시각")
        .baseUnit("seconds")
        .register(registry)
    private var previousJobs = emptyList<JobSnapshot>()

    @Scheduled(fixedDelayString = $$"${ticketing.batch.metrics.refresh-interval:10s}")
    fun refresh() {
        val jobs = try {
            loadJobs()
        } catch (exception: DataAccessException) {
            logger.warn("Batch Job 지표 조회 실패", exception)
            return
        }
        if (jobs == previousJobs) return

        failed.register(jobs.map { MultiGauge.Row.of(Tags.of("job", it.name), if (it.failed) 1 else 0) }, true)
        lastSuccess.register(
            jobs.mapNotNull { job ->
                job.lastSuccess?.let { MultiGauge.Row.of(Tags.of("job", job.name), it) }
            },
            true,
        )
        previousJobs = jobs
    }

    private fun loadJobs(): List<JobSnapshot> {
        // DISTINCT ON이 가장 최근 실행만 남기기 전에 윈도 함수가 Job별 마지막 성공을 계산한다.
        val lastSuccessTime = DSL.max(END_TIME).filterWhere(STATUS.eq("COMPLETED")).over(DSL.partitionBy(JOB_NAME))
        return dsl.select(JOB_NAME, STATUS, lastSuccessTime)
            .distinctOn(JOB_NAME)
            .from(INSTANCES)
            .join(EXECUTIONS).on(INSTANCE_ID.eq(EXECUTION_INSTANCE_ID))
            .where(JOB_NAME.`in`(jobNames), END_TIME.isNotNull, STATUS.`in`("COMPLETED", "FAILED", "ABANDONED", "STOPPED"))
            .orderBy(JOB_NAME, END_TIME.desc(), EXECUTION_ID.desc())
            .fetch { record ->
                // Spring Batch는 JVM 기본 시간대의 LocalDateTime을 TIMESTAMP 열에 저장한다.
                val lastSuccessEpoch = record.value3()?.atZone(ZoneId.systemDefault())?.toEpochSecond()
                JobSnapshot(record.value1(), record.value2() in FAILED_STATUSES, lastSuccessEpoch)
            }
    }

    private data class JobSnapshot(val name: String, val failed: Boolean, val lastSuccess: Long?)

    private companion object {
        val FAILED_STATUSES = setOf("FAILED", "ABANDONED")
        val INSTANCES = DSL.table(DSL.name("batch", "batch_job_instance")).`as`("job_instance")
        val EXECUTIONS = DSL.table(DSL.name("batch", "batch_job_execution")).`as`("job_execution")
        val INSTANCE_ID = DSL.field(DSL.name("job_instance", "job_instance_id"), Long::class.java)
        val JOB_NAME = DSL.field(DSL.name("job_instance", "job_name"), String::class.java)
        val EXECUTION_INSTANCE_ID = DSL.field(DSL.name("job_execution", "job_instance_id"), Long::class.java)
        val EXECUTION_ID = DSL.field(DSL.name("job_execution", "job_execution_id"), Long::class.java)
        val STATUS = DSL.field(DSL.name("job_execution", "status"), String::class.java)
        val END_TIME = DSL.field(DSL.name("job_execution", "end_time"), LocalDateTime::class.java)
    }
}
