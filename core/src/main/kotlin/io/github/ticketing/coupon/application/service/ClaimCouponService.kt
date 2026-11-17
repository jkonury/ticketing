package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.application.port.provided.ClaimCouponUseCase
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.IssueMode
import io.github.ticketing.coupon.domain.MemberId
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 받기 진입점. 트랜잭션은 하위 빈이 잡는다(LIMITED의 Redis 호출을 트랜잭션 안에 두지 않기 위해).
 * UNLIMITED는 [UnlimitedCouponClaimer], LIMITED는 설정한 전략의 [LimitedCouponClaimer]에 위임한다.
 */
@Service
class ClaimCouponService internal constructor(
    private val definitions: CouponDefinitionCommandPort,
    private val unlimitedClaimer: UnlimitedCouponClaimer,
    private val limitedClaimer: LimitedCouponClaimer,
    private val clock: Clock,
) : ClaimCouponUseCase {
    override fun claim(memberId: Long, definitionId: Long): IssuedCouponView {
        val now = clock.instant()
        val definition = definitions.findById(CouponDefinitionId(definitionId)) ?: throw couponNotFound()
        // 중지·기간 밖과 DIRECT는 Redis나 DB 재고를 건드리기 전에 거부한다
        if (!definition.canIssue(now) || definition.issueMode == IssueMode.DIRECT) throw couponNotIssuable()

        val member = MemberId(memberId)
        return if (definition.issueMode == IssueMode.UNLIMITED) {
            unlimitedClaimer.claim(definition, member, now)
        } else {
            limitedView(limitedClaimer.claim(definition, member, now), definition.validUntil, now)
        }
    }

    private fun limitedView(result: LimitedClaimResult, validUntil: Instant, now: Instant): IssuedCouponView = when (result) {
        is LimitedClaimResult.Issued -> result.coupon.toView(validUntil, now)
        LimitedClaimResult.AlreadyClaimed -> throw couponAlreadyClaimed()
        LimitedClaimResult.SoldOut -> throw couponSoldOut()
    }
}
