package io.github.ticketing.coupon

import io.github.ticketing.MutableClock
import io.github.ticketing.coupon.application.port.provided.ClaimCouponUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.DiscountPolicy
import io.github.ticketing.coupon.domain.DiscountType
import io.github.ticketing.coupon.domain.IssueMode
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.MemberId
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.temporal.ChronoUnit

/** 기본 전략(redis)이라 공용 컨텍스트를 그대로 쓴다. */
class LimitedCouponClaimRedisTests(
    claimUseCase: ClaimCouponUseCase,
    definitions: CouponDefinitionCommandPort,
    issuedCoupons: IssuedCouponCommandPort,
    dsl: DSLContext,
    redis: StringRedisTemplate,
    clock: MutableClock,
) : LimitedCouponClaimScenarios(claimUseCase, definitions, issuedCoupons, dsl, redis, clock) {
    @Test
    fun `무제한 쿠폰의 첫 발급이 동시에 일어나도 모든 회원이 받는다`() {
        val definitionId = unlimitedDefinition()
        val outcomes = claimConcurrently(definitionId, List(100) { nextMemberId() })

        assertThat(outcomes).containsOnly("OK")
        assertThat(issuedCount(definitionId)).isEqualTo(100)
        assertThat(definitionRow(definitionId).version).isEqualTo(1L)
    }

    @Test
    fun `무제한 쿠폰을 같은 회원이 동시에 받아도 한 건만 발급된다`() {
        val definitionId = unlimitedDefinition()
        val memberId = nextMemberId()
        val outcomes = claimConcurrently(definitionId, List(20) { memberId })

        assertThat(outcomes.count { it == "OK" }).isEqualTo(1)
        assertThat(outcomes.filter { it != "OK" }).containsOnly("COUPON_ALREADY_CLAIMED")
        assertThat(issuedCount(definitionId)).isEqualTo(1)
    }

    private fun unlimitedDefinition(): Long {
        val policy = DiscountPolicy(
            DiscountType.FIXED,
            BigDecimal(1000),
            null,
            BigDecimal.ZERO,
            null,
            now.minus(1, ChronoUnit.DAYS),
            now.plus(30, ChronoUnit.DAYS),
        )
        return requireNotNull(definitions.save(CouponDefinition.create("무제한 쿠폰", null, policy, IssueMode.UNLIMITED, null, now)).id).value
    }

    @Test
    fun `Redis 키가 없으면 DB 발급 현황을 기준으로 재고와 회원 Set을 만든다`() {
        val definitionId = limitedDefinition(quantity = 5)
        val alreadyClaimed = listOf(nextMemberId(), nextMemberId())
        alreadyClaimed.forEach { issuedCoupons.issue(IssuedCoupon.issue(CouponDefinitionId(definitionId), MemberId(it), now)) }

        val newcomer = nextMemberId()
        assertThat(claimOutcome(newcomer, definitionId)).isEqualTo("OK")

        assertThat(redis.opsForValue().get(stockKey(definitionId))).isEqualTo("2")
        assertThat(redis.opsForSet().members(claimedKey(definitionId)))
            .containsExactlyInAnyOrder(*(alreadyClaimed + newcomer).map { it.toString() }.toTypedArray())
        assertThat(claimOutcome(alreadyClaimed.first(), definitionId)).isEqualTo("COUPON_ALREADY_CLAIMED")
    }

    @Test
    fun `Redis Set이 유실된 회원이 다시 받으면 이미 받음으로 거절되고 재고는 복구된다`() {
        val definitionId = limitedDefinition(quantity = 3)
        val memberId = nextMemberId()
        assertThat(claimOutcome(memberId, definitionId)).isEqualTo("OK")
        assertThat(redis.opsForValue().get(stockKey(definitionId))).isEqualTo("2")
        redis.opsForSet().remove(claimedKey(definitionId), memberId.toString())

        assertThat(claimOutcome(memberId, definitionId)).isEqualTo("COUPON_ALREADY_CLAIMED")

        assertThat(redis.opsForValue().get(stockKey(definitionId))).isEqualTo("2")
        assertThat(issuedCount(definitionId)).isEqualTo(1)
    }

    @Test
    fun `Redis 재고만 0이어도 DB 발급 수를 넘지 않는다`() {
        val definitionId = limitedDefinition(quantity = 1)
        assertThat(claimOutcome(nextMemberId(), definitionId)).isEqualTo("OK")

        assertThat(claimOutcome(nextMemberId(), definitionId)).isEqualTo("COUPON_SOLD_OUT")

        assertThat(issuedCount(definitionId)).isEqualTo(1)
    }
}
