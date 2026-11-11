package io.github.ticketing.booking.adapter.provided.batch

import io.github.ticketing.KeysetItemReader
import io.github.ticketing.booking.application.port.provided.ReconcileSeatSnapshotUseCase
import io.github.ticketing.booking.application.port.required.SeatSnapshotReconcileException
import io.github.ticketing.booking.application.service.SeatSnapshotQueries
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
import java.time.Instant

@Configuration
@Profile("batch")
class ReconcileSeatSnapshotJobConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun reconcileSeatSnapshotJob(repository: JobRepository, reconcileSeatSnapshotStep: Step): Job =
        JobBuilder("reconcileSeatSnapshotJob", repository).start(reconcileSeatSnapshotStep).build()

    @Bean
    fun reconcileSeatSnapshotStep(
        repository: JobRepository,
        reconcileSeatSnapshotReader: ItemStreamReader<Long>,
        useCase: ReconcileSeatSnapshotUseCase,
        clock: Clock,
    ): Step = ChunkOrientedStepBuilder<Long, Long>("reconcileSeatSnapshotStep", repository, CHUNK_SIZE)
        .reader(reconcileSeatSnapshotReader)
        .writer(
            ItemWriter { chunk ->
                chunk.items.forEach { id ->
                    try {
                        val changed = useCase.reconcile(id)
                        val context = requireNotNull(org.springframework.batch.core.scope.context.StepSynchronizationManager.getContext())
                            .stepExecution.executionContext
                        context.putLong("changed", context.getLong("changed", 0) + changed)
                        log.info("좌석 스냅샷 보정 scheduleId={} changed={}", id, changed)
                    } catch (exception: SeatSnapshotReconcileException) {
                        log.warn("좌석 스냅샷 보정 실패 scheduleId={}", id, exception)
                    }
                }
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

            override fun afterStep(stepExecution: StepExecution): org.springframework.batch.core.ExitStatus? {
                log.info("좌석 스냅샷 보정 합계 changed={}", stepExecution.executionContext.getLong("changed", 0))
                return null
            }
        })
        .build()

    @Bean
    @StepScope
    fun reconcileSeatSnapshotReader(
        queries: SeatSnapshotQueries,
        @Value("#{stepExecutionContext['cutoff']}") cutoff: String,
    ): ItemStreamReader<Long> {
        val instant = Instant.parse(cutoff)
        return KeysetItemReader("reconcileSeatSnapshotReader", CHUNK_SIZE, { it }) { after, limit ->
            queries.upcoming(instant, after, limit)
        }
    }
}

private const val CHUNK_SIZE = 100
