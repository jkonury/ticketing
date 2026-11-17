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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean

@TestPropertySource(properties = ["ticketing.coupon.limited-claim.strategy=redis"])
@MockitoSpyBean(types = [IssuedCouponCommandPort::class])
class LimitedCouponClaimDbFailureTests(
    claimUseCase: ClaimCouponUseCase,
    definitions: CouponDefinitionCommandPort,
    issuedCoupons: IssuedCouponCommandPort,
    dsl: DSLContext,
    redis: StringRedisTemplate,
    clock: MutableClock,
) : LimitedCouponClaimTestSupport(claimUseCase, definitions, issuedCoupons, dsl, redis, clock) {
    @Test
    fun `DB INSERT가 실패하면 재고와 회원 표시를 되돌리고 예외를 전파한다`() {
        val definitionId = limitedDefinition(quantity = 3)
        val memberId = nextMemberId()
        // 오픈(초기화)까지 거친 상태에서 실패를 주입한다
        assertThat(claimOutcome(nextMemberId(), definitionId)).isEqualTo("OK")
        try {
            doThrow(
                DataAccessResourceFailureException("주입된 DB 장애"),
            ).`when`(issuedCoupons).issue(any(IssuedCoupon::class.java) ?: placeholder())

            assertThat(claimOutcome(memberId, definitionId)).isEqualTo("DataAccessResourceFailureException")
        } finally {
            reset(issuedCoupons)
        }

        assertThat(redis.opsForValue().get(stockKey(definitionId))).isEqualTo("2")
        assertThat(redis.opsForSet().isMember(claimedKey(definitionId), memberId.toString())).isFalse()
        assertThat(claimOutcome(memberId, definitionId)).isEqualTo("OK")
    }

    // Mockito any()는 null을 돌려주므로 Kotlin 비null 파라미터에는 자리 표시용 값을 대신 넘긴다
    private fun placeholder(): IssuedCoupon = IssuedCoupon.issue(CouponDefinitionId(0), MemberId(0), now)
}
