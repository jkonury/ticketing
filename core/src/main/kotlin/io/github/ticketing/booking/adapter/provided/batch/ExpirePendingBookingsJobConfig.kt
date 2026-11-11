package io.github.ticketing.booking.adapter.provided.batch

import io.github.ticketing.KeysetItemReader
import io.github.ticketing.booking.application.port.provided.ExpireBookingUseCase
import io.github.ticketing.booking.application.port.provided.ExpireResult
import io.github.ticketing.booking.application.port.required.BookingQueryPort
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
import org.springframework.dao.OptimisticLockingFailureException
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Configuration
@Profile("batch")
class ExpirePendingBookingsJobConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun expirePendingBookingsJob(repository: JobRepository, expirePendingBookingsStep: Step): Job =
        JobBuilder("expirePendingBookingsJob", repository).start(expirePendingBookingsStep).build()

    @Bean
    fun expirePendingBookingsStep(
        repository: JobRepository,
        expirePendingBookingsReader: ItemStreamReader<Long>,
        useCase: ExpireBookingUseCase,
        clock: Clock,
    ): Step = ChunkOrientedStepBuilder<Long, Long>("expirePendingBookingsStep", repository, CHUNK_SIZE)
        .reader(expirePendingBookingsReader)
        .writer(
            ItemWriter { chunk ->
                var expired = 0
                var skipped = 0
                chunk.items.forEach { id ->
                    try {
                        when (useCase.expireIfDue(id)) {
                            ExpireResult.Expired -> expired++
                            is ExpireResult.Skipped -> skipped++
                        }
                    } catch (exception: OptimisticLockingFailureException) {
                        skipped++
                        log.warn("만료 처리 중 동시 수정으로 건너뜀 bookingId={}", id, exception)
                    }
                }
                log.info("만료 예매 처리 expired={} skipped={}", expired, skipped)
            },
        )
        .listener(object : StepExecutionListener {
            override fun beforeStep(stepExecution: StepExecution) {
                val context = stepExecution.executionContext
                if (!context.containsKey("cutoff")) {
                    context.putString("cutoff", clock.instant().toString())
                    repository.updateExecutionContext(stepExecution)
                }
            }
        })
        .build()

    @Bean
    @StepScope
    fun expirePendingBookingsReader(
        queryPort: BookingQueryPort,
        @Value("#{stepExecutionContext['cutoff']}") cutoff: String,
        @Value("\${ticketing.booking.expiry.payment-grace:30m}") paymentGrace: Duration,
    ): ItemStreamReader<Long> {
        require(!paymentGrace.isNegative)
        val instant = Instant.parse(cutoff)
        return KeysetItemReader("expirePendingBookingsReader", CHUNK_SIZE, { it }) { after, limit ->
            queryPort.findSweepCandidateIds(instant, instant.minus(paymentGrace), after, limit)
        }
    }
}

private const val CHUNK_SIZE = 100
