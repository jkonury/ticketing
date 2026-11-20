package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.application.port.required.IssueResult
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.MemberId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
internal class UnlimitedCouponClaimer(private val issuedCoupons: IssuedCouponCommandPort) {
    @Transactional
    fun claim(definition: CouponDefinition, memberId: MemberId, now: Instant): IssuedCouponView {
        val definitionId = checkNotNull(definition.id) { "저장된 쿠폰 정의여야 한다" }
        val result = issuedCoupons.issue(IssuedCoupon.issue(definitionId, memberId, now))
        val issued = (result as? IssueResult.Issued)?.coupon ?: throw couponAlreadyClaimed()

        return issued.toView(definition.validUntil, now)
    }
}
