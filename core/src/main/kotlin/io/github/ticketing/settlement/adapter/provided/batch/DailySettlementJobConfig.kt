package io.github.ticketing.settlement.adapter.provided.batch

import io.github.ticketing.KeysetItemReader
import io.github.ticketing.settlement.application.port.required.DailySettlementCommandPort
import io.github.ticketing.settlement.application.port.required.PerformanceTotals
import io.github.ticketing.settlement.application.port.required.SettlementQueryPort
import io.github.ticketing.settlement.domain.DailySettlement
import io.github.ticketing.settlement.domain.DailySettlementCalculator
import io.github.ticketing.settlement.domain.FeeRate
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.InvalidJobParametersException
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

@ConfigurationProperties("ticketing.settlement")
data class SettlementProperties(val feeRate: BigDecimal = BigDecimal("0.10"), val chunkSize: Int = 100) {
    init {
        FeeRate(feeRate)
        require(chunkSize > 0)
    }
}

@Configuration
@Profile("batch")
@EnableConfigurationProperties(SettlementProperties::class)
class DailySettlementJobConfig {
    @Bean
    fun dailySettlementJob(repository: JobRepository, dailySettlementStep: Step): Job = JobBuilder("dailySettlementJob", repository)
        .validator { parameters ->
            val targetDate = parameters.getParameter("targetDate")
                ?: throw InvalidJobParametersException("targetDate is required")
            if (!targetDate.identifying()) throw InvalidJobParametersException("targetDate must be identifying")
            try {
                LocalDate.parse(targetDate.value().toString())
            } catch (exception: java.time.format.DateTimeParseException) {
                throw InvalidJobParametersException("targetDate must be an ISO date")
            }
        }.start(dailySettlementStep).build()

    @Bean
    fun dailySettlementStep(
        repository: JobRepository,
        properties: SettlementProperties,
        dailySettlementReader: ItemStreamReader<PerformanceTotals>,
        dailySettlementProcessor: ItemProcessor<PerformanceTotals, DailySettlement>,
        commandPort: DailySettlementCommandPort,
    ): Step = ChunkOrientedStepBuilder<PerformanceTotals, DailySettlement>("dailySettlementStep", repository, properties.chunkSize)
        .reader(dailySettlementReader)
        .processor(dailySettlementProcessor)
        .writer(ItemWriter { chunk -> commandPort.upsertAll(chunk.items) })
        .build()

    @Bean
    @StepScope
    fun dailySettlementReader(
        queryPort: SettlementQueryPort,
        properties: SettlementProperties,
        @Value("#{jobParameters['targetDate']}") targetDate: Any,
    ): ItemStreamReader<PerformanceTotals> = KeysetItemReader(
        "dailySettlementReader",
        properties.chunkSize,
        PerformanceTotals::performanceId,
    ) { after, limit -> queryPort.findPerformanceTotals(parseDate(targetDate), after, limit) }

    @Bean
    @StepScope
    fun dailySettlementProcessor(
        properties: SettlementProperties,
        clock: Clock,
        @Value("#{jobParameters['targetDate']}") targetDate: Any,
    ): ItemProcessor<PerformanceTotals, DailySettlement> = ItemProcessor { totals ->
        DailySettlementCalculator.settle(
            parseDate(targetDate), totals.performanceId, totals.organizerId, totals.saleCount, totals.saleAmount,
            totals.refundCount, totals.refundAmount, FeeRate(properties.feeRate), clock.instant(),
        )
    }
}

private fun parseDate(value: Any): LocalDate = if (value is LocalDate) value else LocalDate.parse(value.toString())
