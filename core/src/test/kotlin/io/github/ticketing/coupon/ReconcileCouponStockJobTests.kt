package io.github.ticketing.coupon

import io.github.ticketing.MutableClock
import io.github.ticketing.coupon.application.port.provided.ClaimCouponUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.MemberId
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.atomic.AtomicLong

class ReconcileCouponStockJobTests(
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
    fun `Redis 재고가 DB 기준보다 많으면 낮춘다`() {
        val definitionId = openedDefinition(quantity = 5)
        val members = issueInDb(definitionId, 2)
        seed(definitionId, stock = "5", members = members)

        runJob()

        assertThat(stock(definitionId)).isEqualTo("3")
    }

    @Test
    fun `Redis 재고가 DB 기준보다 적으면 올리지 않는다`() {
        val definitionId = openedDefinition(quantity = 5)
        val members = issueInDb(definitionId, 2)
        seed(definitionId, stock = "1", members = members)

        runJob()

        assertThat(stock(definitionId)).isEqualTo("1")
    }

    @Test
    fun `Set에 빠진 발급 회원은 더하고 Set에만 있는 회원은 지우지 않는다`() {
        val definitionId = openedDefinition(quantity = 5)
        val members = issueInDb(definitionId, 2)
        val inFlight = nextMemberId()
        seed(definitionId, stock = "2", members = listOf(members[0], inFlight))

        runJob()

        assertThat(redis.opsForSet().members(claimedKey(definitionId)))
            .containsExactlyInAnyOrder(members[0].toString(), members[1].toString(), inFlight.toString())
        assertThat(stock(definitionId)).isEqualTo("2")
    }

    @Test
    fun `키가 없으면 DB 기준으로 만든다`() {
        val definitionId = openedDefinition(quantity = 5)
        val members = issueInDb(definitionId, 2)

        runJob()

        assertThat(stock(definitionId)).isEqualTo("3")
        assertThat(redis.opsForSet().members(claimedKey(definitionId)))
            .containsExactlyInAnyOrder(*members.map { it.toString() }.toTypedArray())
    }

    @Test
    fun `발급이 시작되지 않은 정의는 건드리지 않는다`() {
        val definitionId = limitedDefinition(quantity = 5)

        runJob()

        assertThat(stock(definitionId)).isNull()
    }

    private fun runJob() {
        val parameters = JobParametersBuilder().addLong("run.id", runSequence.incrementAndGet()).toJobParameters()
        assertThat(operator.start(reconcileCouponStockJob, parameters).status).isEqualTo(BatchStatus.COMPLETED)
    }

    private fun openedDefinition(quantity: Int): Long {
        val id = limitedDefinition(quantity)
        val definition = requireNotNull(definitions.findById(CouponDefinitionId(id)))
        definition.markIssueStarted(now)
        definitions.save(definition)
        return id
    }

    private fun issueInDb(definitionId: Long, count: Int): List<Long> = List(count) { nextMemberId() }.onEach {
        issuedCoupons.issue(IssuedCoupon.issue(CouponDefinitionId(definitionId), MemberId(it), now))
    }

    private fun seed(definitionId: Long, stock: String, members: List<Long>) {
        redis.opsForValue().set(stockKey(definitionId), stock)
        redis.opsForSet().add(claimedKey(definitionId), *members.map { it.toString() }.toTypedArray())
    }

    private fun stock(definitionId: Long): String? = redis.opsForValue().get(stockKey(definitionId))

    private companion object {
        val runSequence = AtomicLong(System.nanoTime())
    }
}
