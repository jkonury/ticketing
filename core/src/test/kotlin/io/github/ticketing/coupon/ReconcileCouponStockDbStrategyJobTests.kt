package io.github.ticketing.coupon

import io.github.ticketing.MutableClock
import io.github.ticketing.coupon.application.port.provided.ClaimCouponUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinitionId
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["ticketing.coupon.limited-claim.strategy=db"])
class ReconcileCouponStockDbStrategyJobTests(
    private val operator: JobOperator,
    private val reconcileCouponStockJob: Job,
    claimUseCase: ClaimCouponUseCase,
    definitions: CouponDefinitionCommandPort,
    issuedCoupons: IssuedCouponCommandPort,
    dsl: DSLContext,
    redis: StringRedisTemplate,
    clock: MutableClock,
) : LimitedCouponClaimTestSupport(claimUseCase, definitions, issuedCoupons, dsl, redis, clock) {
    @Test
    fun `db 전략이면 Redis를 건드리지 않고 완료한다`() {
        val definitionId = limitedDefinition(quantity = 5)
        val definition = requireNotNull(definitions.findById(CouponDefinitionId(definitionId)))
        definition.markIssueStarted(now)
        definitions.save(definition)
        redis.opsForValue().set(stockKey(definitionId), "5")

        val parameters = JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters()
        assertThat(operator.start(reconcileCouponStockJob, parameters).status).isEqualTo(BatchStatus.COMPLETED)

        assertThat(redis.opsForValue().get(stockKey(definitionId))).isEqualTo("5")
        assertThat(redis.opsForSet().members(claimedKey(definitionId))).isEmpty()
    }
}
