package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.application.port.provided.ClaimCouponUseCase
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.IssueResult
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.IssueMode
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.MemberId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class ClaimCouponService(
    private val definitions: CouponDefinitionCommandPort,
    private val issuedCoupons: IssuedCouponCommandPort,
    private val clock: Clock,
) : ClaimCouponUseCase {

    /** UNLIMITED만 받을 수 있다. LIMITED(선착순)는 M5.5-3 전까지 받을 수 없는 쿠폰으로 취급한다. */
    @Transactional
    override fun claim(memberId: Long, definitionId: Long): IssuedCouponView {
        val now = clock.instant()
        val definition = loadClaimable(definitionId, now)

        val result = issuedCoupons.issue(IssuedCoupon.issue(CouponDefinitionId(definitionId), MemberId(memberId), now))
        val issued = (result as? IssueResult.Issued)?.coupon ?: throw couponAlreadyClaimed()

        if (!definition.issueStarted) {
            definition.markIssueStarted(now)
            definitions.save(definition)
        }
        return issued.toView(definition.validUntil, now)
    }

    private fun loadClaimable(definitionId: Long, now: Instant): CouponDefinition {
        val definition = definitions.findById(CouponDefinitionId(definitionId)) ?: throw couponNotFound()
        if (definition.issueMode != IssueMode.UNLIMITED || !definition.canIssue(now)) throw couponNotIssuable()
        return definition
    }
}
