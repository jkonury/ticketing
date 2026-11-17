package io.github.ticketing.coupon.adapter.provided.batch

import io.github.ticketing.KeysetItemReader
import io.github.ticketing.coupon.application.port.provided.ReconcileCouponStockUseCase
import io.github.ticketing.coupon.application.port.required.OpenLimitedDefinitionQueryPort
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** 발급이 시작된 LIMITED 쿠폰의 Redis 재고·회원 Set을 DB 기준으로 보수적으로 보정한다. redis 전략이 아니면 아무것도 바꾸지 않는다. */
@Configuration
@Profile("batch")
class ReconcileCouponStockJobConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun reconcileCouponStockJob(repository: JobRepository, reconcileCouponStockStep: Step): Job =
        JobBuilder("reconcileCouponStockJob", repository).start(reconcileCouponStockStep).build()

    @Bean
    fun reconcileCouponStockStep(
        repository: JobRepository,
        queryPort: OpenLimitedDefinitionQueryPort,
        useCase: ReconcileCouponStockUseCase,
    ): Step = ChunkOrientedStepBuilder<Long, Long>("reconcileCouponStockStep", repository, CHUNK_SIZE)
        .reader(reader(queryPort))
        .writer(
            ItemWriter { chunk ->
                chunk.items.forEach { id -> log.info("쿠폰 재고 보정 definitionId={} changed={}", id, useCase.reconcile(id)) }
            },
        )
        .build()

    private fun reader(queryPort: OpenLimitedDefinitionQueryPort): ItemStreamReader<Long> =
        KeysetItemReader("reconcileCouponStockReader", CHUNK_SIZE, { it }) { after, limit ->
            queryPort.findOpenLimitedIds(after, limit)
        }
}

private const val CHUNK_SIZE = 100
