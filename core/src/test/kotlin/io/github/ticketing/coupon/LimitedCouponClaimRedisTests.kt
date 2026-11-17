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
import org.springframework.data.redis.core.StringRedisTemplate

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
