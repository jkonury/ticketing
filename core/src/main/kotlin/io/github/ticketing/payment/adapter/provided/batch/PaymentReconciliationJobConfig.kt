package io.github.ticketing.payment.adapter.provided.batch

import io.github.ticketing.KeysetItemReader
import io.github.ticketing.payment.adapter.required.jooq.ReconciliationGroup
import io.github.ticketing.payment.adapter.required.jooq.ReconciliationResult
import io.github.ticketing.payment.adapter.required.jooq.ReconciliationStore
import io.github.ticketing.payment.application.port.required.PgTransactionQueryPort
import io.github.ticketing.payment.domain.ReconciliationRule
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.InvalidJobParametersException
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.DefaultTransactionAttribute
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

/** 불일치를 별도 단계에서 삭제해 청크 재시작 시 이미 커밋한 결과를 보존한다. */
@Configuration
@Profile("batch")
class PaymentReconciliationJobConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun paymentReconciliationJob(
        repository: JobRepository,
        fetchPgTransactionsStep: Step,
        clearMismatchesStep: Step,
        detectMismatchesStep: Step,
    ): Job = JobBuilder("paymentReconciliationJob", repository).validator { parameters ->
        val date = parameters.getParameter("targetDate") ?: throw InvalidJobParametersException("targetDate is required")
        if (!date.identifying()) throw InvalidJobParametersException("targetDate must be identifying")
        try {
            LocalDate.parse(date.value().toString())
        } catch (exception: java.time.format.DateTimeParseException) {
            throw InvalidJobParametersException("targetDate must be an ISO date")
        }
    }.start(fetchPgTransactionsStep).next(clearMismatchesStep).next(detectMismatchesStep).build()

    @Bean
    fun fetchPgTransactionsStep(
        repository: JobRepository,
        manager: PlatformTransactionManager,
        pg: PgTransactionQueryPort,
        store: ReconciliationStore,
    ): Step = StepBuilder("fetchPgTransactionsStep", repository).tasklet({ _, context ->
        val date = parseDate(requireNotNull(context.stepContext.jobParameters["targetDate"]))
        val transactions = pg.findTransactions(date)
        TransactionTemplate(manager).executeWithoutResult { store.replaceSnapshots(date, transactions) }
        RepeatStatus.FINISHED
    }, manager).transactionAttribute(DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED)).build()

    @Bean
    fun clearMismatchesStep(repository: JobRepository, manager: PlatformTransactionManager, store: ReconciliationStore): Step =
        StepBuilder("clearMismatchesStep", repository).tasklet({ _, context ->
            store.clear(parseDate(requireNotNull(context.stepContext.jobParameters["targetDate"])))
            RepeatStatus.FINISHED
        }, manager).build()

    @Bean
    fun detectMismatchesStep(
        repository: JobRepository,
        paymentReconciliationReader: ItemStreamReader<ReconciliationGroup>,
        paymentReconciliationWriter: ItemWriter<List<ReconciliationResult>>,
        store: ReconciliationStore,
    ): Step = ChunkOrientedStepBuilder<ReconciliationGroup, List<ReconciliationResult>>("detectMismatchesStep", repository, CHUNK_SIZE)
        .reader(paymentReconciliationReader)
        .processor(
            ItemProcessor { group ->
                group.rows.map { row -> ReconciliationResult(row, ReconciliationRule.compare(row.internal, row.pg)) }
            },
        )
        .writer(paymentReconciliationWriter)
        .listener(object : StepExecutionListener {
            override fun afterStep(stepExecution: StepExecution): ExitStatus? {
                val date = parseDate(requireNotNull(stepExecution.jobParameters.getParameter("targetDate")).value())
                log.info("결제 대사 불일치 targetDate={} counts={}", date, store.counts(date))
                return null
            }
        }).build()

    @Bean
    @StepScope
    fun paymentReconciliationReader(
        store: ReconciliationStore,
        @Value("#{jobParameters['targetDate']}") targetDate: Any,
    ): ItemStreamReader<ReconciliationGroup> =
        KeysetItemReader("paymentReconciliationReader", CHUNK_SIZE, ReconciliationGroup::orderId) { after, limit ->
            store.find(parseDate(targetDate), after, limit)
        }

    @Bean
    @StepScope
    fun paymentReconciliationWriter(
        store: ReconciliationStore,
        @Value("#{jobParameters['targetDate']}") targetDate: Any,
    ): ItemWriter<List<ReconciliationResult>> = ItemWriter { chunk ->
        val date = parseDate(targetDate)
        store.insert(date, chunk.items.flatten())
    }
}

private const val CHUNK_SIZE = 100
private fun parseDate(value: Any): LocalDate = if (value is LocalDate) value else LocalDate.parse(value.toString())
