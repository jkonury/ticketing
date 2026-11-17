package io.github.ticketing.coupon.application.port.required

import io.github.ticketing.coupon.application.port.provided.AdminCouponView
import io.github.ticketing.coupon.application.port.provided.ClaimableCouponView
import io.github.ticketing.coupon.application.port.provided.CursorPage
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.application.port.provided.MyCouponView
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import java.time.Instant

/** 조회 전용. EXPIRED는 저장 상태가 아니어도 ISSUED이고 유효 기간이 지났으면 now 기준으로 계산해 돌려준다. */
interface CouponQueryPort {
    fun findDefinitions(cursor: Long?, size: Int): CursorPage<AdminCouponView>

    fun findDefinition(definitionId: Long): AdminCouponView?

    fun findIssued(definitionId: Long, cursor: Long?, size: Int, status: IssuedCouponStatus?, now: Instant): CursorPage<IssuedCouponView>

    fun findIssuedById(issuedCouponId: Long, now: Instant): IssuedCouponView?

    fun findClaimable(memberId: Long, now: Instant): List<ClaimableCouponView>

    fun findMyCoupons(memberId: Long, now: Instant): List<MyCouponView>
}
