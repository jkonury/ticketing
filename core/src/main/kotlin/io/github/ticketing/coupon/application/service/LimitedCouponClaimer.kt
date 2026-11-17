package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.MemberId
import java.time.Instant

sealed interface LimitedClaimResult {
    data class Issued(val coupon: IssuedCoupon) : LimitedClaimResult

    data object AlreadyClaimed : LimitedClaimResult

    data object SoldOut : LimitedClaimResult
}

/**
 * 선착순(LIMITED) 받기 전략. `ticketing.coupon.limited-claim.strategy`(redis|db)로 하나만 활성화된다.
 * 어느 전략도 요청마다 정의를 저장하지 않는다. 발급 시작 표시는 [CouponIssueStarter]가 오픈 때 한 번만 남긴다.
 */
internal interface LimitedCouponClaimer {
    fun claim(definition: CouponDefinition, memberId: MemberId, now: Instant): LimitedClaimResult
}
