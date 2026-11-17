package io.github.ticketing.coupon.adapter.provided.batch

import io.github.ticketing.KeysetItemReader
import io.github.ticketing.coupon.application.port.provided.ReleaseStaleCouponReservationUseCase
import io.github.ticketing.coupon.application.port.required.StaleReservationQueryPort
import org.slf4j.LoggerFactory
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** booking 이벤트 유실로 RESERVED에 남은 쿠폰을 시간 기준으로만 복원한다. booking을 조회하거나 이벤트를 발행하지 않는다. */
@Configuration
@Profile("batch")
class ReleaseStaleCouponReservationsJobConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun releaseStaleCouponReservationsJob(repository: JobRepository, releaseStaleCouponReservationsStep: Step): Job =
        JobBuilder("releaseStaleCouponReservationsJob", repository).start(releaseStaleCouponReservationsStep).build()

    @Bean
    fun releaseStaleCouponReservationsStep(
        repository: JobRepository,
        releaseStaleCouponReservationsReader: ItemStreamReader<Long>,
        releaseStaleCouponReservationsWriter: ItemWriter<Long>,
        clock: Clock,
        @Value("\${ticketing.coupon.reservation-release-grace:10m}") grace: Duration,
    ): Step {
        require(!grace.isNegative)
        return ChunkOrientedStepBuilder<Long, Long>("releaseStaleCouponReservationsStep", repository, CHUNK_SIZE)
            .reader(releaseStaleCouponReservationsReader)
            .writer(releaseStaleCouponReservationsWriter)
            .listener(object : StepExecutionListener {
                override fun beforeStep(stepExecution: StepExecution) {
                    val context = stepExecution.executionContext
                    if (!context.containsKey(CUTOFF)) {
                        context.putString(CUTOFF, clock.instant().minus(grace).toString())
                        repository.updateExecutionContext(stepExecution)
                    }
                }
            })
            .build()
    }

    @Bean
    @StepScope
    fun releaseStaleCouponReservationsReader(
        queryPort: StaleReservationQueryPort,
        @Value("#{stepExecutionContext['cutoff']}") cutoff: String,
    ): ItemStreamReader<Long> {
        val instant = Instant.parse(cutoff)
        return KeysetItemReader("releaseStaleCouponReservationsReader", CHUNK_SIZE, { it }) { after, limit ->
            queryPort.findStaleReservationIds(instant, after, limit)
        }
    }

    @Bean
    @StepScope
    fun releaseStaleCouponReservationsWriter(
        useCase: ReleaseStaleCouponReservationUseCase,
        @Value("#{stepExecutionContext['cutoff']}") cutoff: String,
    ): ItemWriter<Long> {
        val instant = Instant.parse(cutoff)
        return ItemWriter { chunk ->
            val released = chunk.items.count { useCase.releaseIfStale(it, instant) }
            log.info("고아 쿠폰 예약 정리 released={} skipped={}", released, chunk.items.size - released)
        }
    }
}

private const val CUTOFF = "cutoff"
private const val CHUNK_SIZE = 100
