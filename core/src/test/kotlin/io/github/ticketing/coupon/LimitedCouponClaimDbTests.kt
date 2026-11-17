package io.github.ticketing.coupon

import io.github.ticketing.MutableClock
import io.github.ticketing.coupon.application.port.provided.ClaimCouponUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["ticketing.coupon.limited-claim.strategy=db"])
class LimitedCouponClaimDbTests(
    claimUseCase: ClaimCouponUseCase,
    definitions: CouponDefinitionCommandPort,
    issuedCoupons: IssuedCouponCommandPort,
    dsl: DSLContext,
    redis: StringRedisTemplate,
    clock: MutableClock,
) : LimitedCouponClaimScenarios(claimUseCase, definitions, issuedCoupons, dsl, redis, clock) {
    private fun remaining(definitionId: Long): Int? = definitionRow(definitionId).remainingQuantity

    @Test
    fun `생성 직후 남은 수량은 총 수량이고 받을 때마다 줄어든다`() {
        val definitionId = limitedDefinition(quantity = 3)
        assertThat(remaining(definitionId)).isEqualTo(3)

        claimOutcome(nextMemberId(), definitionId)

        assertThat(remaining(definitionId)).isEqualTo(2)
    }

    @Test
    fun `이미 받은 회원의 요청은 롤백되어 남은 수량이 줄지 않는다`() {
        val definitionId = limitedDefinition(quantity = 3)
        val memberId = nextMemberId()
        claimOutcome(memberId, definitionId)

        assertThat(claimOutcome(memberId, definitionId)).isEqualTo("COUPON_ALREADY_CLAIMED")

        assertThat(remaining(definitionId)).isEqualTo(2)
    }
}
