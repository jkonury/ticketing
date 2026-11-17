package io.github.ticketing.coupon

import io.github.ticketing.MutableClock
import io.github.ticketing.coupon.application.port.provided.ClaimCouponUseCase
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinitionId
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.temporal.ChronoUnit

/** 두 전략(redis, db)이 같은 결과를 내야 하는 시나리오. */
abstract class LimitedCouponClaimScenarios(
    claimUseCase: ClaimCouponUseCase,
    definitions: CouponDefinitionCommandPort,
    issuedCoupons: IssuedCouponCommandPort,
    dsl: DSLContext,
    redis: StringRedisTemplate,
    clock: MutableClock,
) : LimitedCouponClaimTestSupport(claimUseCase, definitions, issuedCoupons, dsl, redis, clock) {
    @Test
    fun `동시에 200명이 수량 50을 받으면 정확히 50건만 발급되고 나머지는 소진이다`() {
        val definitionId = limitedDefinition(quantity = 50)

        val outcomes = claimConcurrently(definitionId, List(200) { nextMemberId() })

        assertThat(outcomes.count { it == "OK" }).isEqualTo(50)
        assertThat(outcomes.count { it == "COUPON_SOLD_OUT" }).isEqualTo(150)
        assertThat(issuedCount(definitionId)).isEqualTo(50)
    }

    @Test
    fun `같은 회원이 동시에 여러 번 받아도 1건만 발급된다`() {
        val definitionId = limitedDefinition(quantity = 10)
        val memberId = nextMemberId()

        val outcomes = claimConcurrently(definitionId, List(20) { memberId })

        assertThat(outcomes.count { it == "OK" }).isEqualTo(1)
        assertThat(outcomes.filter { it != "OK" }).containsOnly("COUPON_ALREADY_CLAIMED")
        assertThat(issuedCount(definitionId)).isEqualTo(1)
    }

    @Test
    fun `같은 회원 중복 요청이 재고를 소모하지 않는다`() {
        val definitionId = limitedDefinition(quantity = 2)
        val memberId = nextMemberId()
        claimOutcome(memberId, definitionId)

        assertThat(claimOutcome(memberId, definitionId)).isEqualTo("COUPON_ALREADY_CLAIMED")

        assertThat(claimOutcome(nextMemberId(), definitionId)).isEqualTo("OK")
        assertThat(claimOutcome(nextMemberId(), definitionId)).isEqualTo("COUPON_SOLD_OUT")
    }

    @Test
    fun `중지된 쿠폰과 기간 밖 쿠폰은 받을 수 없다`() {
        val stopped = limitedDefinition(quantity = 5)
        definitions.save(
            requireNotNull(definitions.findById(CouponDefinitionId(stopped))).apply {
                stop()
            },
        )
        val notYetOpen =
            limitedDefinition(quantity = 5, validFrom = now.plus(1, ChronoUnit.DAYS), validUntil = now.plus(5, ChronoUnit.DAYS))
        val expired = limitedDefinition(quantity = 5, validFrom = now.minus(5, ChronoUnit.DAYS), validUntil = now.minus(1, ChronoUnit.DAYS))

        assertThat(claimOutcome(nextMemberId(), stopped)).isEqualTo("COUPON_NOT_ISSUABLE")
        assertThat(claimOutcome(nextMemberId(), notYetOpen)).isEqualTo("COUPON_NOT_ISSUABLE")
        assertThat(claimOutcome(nextMemberId(), expired)).isEqualTo("COUPON_NOT_ISSUABLE")
        assertThat(issuedCount(stopped) + issuedCount(notYetOpen) + issuedCount(expired)).isZero()
    }

    @Test
    fun `발급 시작 표시는 오픈 때 한 번만 저장되어 동시 받기에서 낙관적 락 실패가 없다`() {
        val definitionId = limitedDefinition(quantity = 30)

        val outcomes = claimConcurrently(definitionId, List(100) { nextMemberId() })

        assertThat(outcomes.toSet()).containsOnly("OK", "COUPON_SOLD_OUT")
        val definition = definitionRow(definitionId)
        assertThat(definition.version).isEqualTo(1L)
        assertThat(definition.issueStartedAt).isNotNull()
    }
}
