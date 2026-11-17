package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.application.port.provided.AdminCouponView
import io.github.ticketing.coupon.application.port.provided.ClaimableCouponView
import io.github.ticketing.coupon.application.port.provided.CouponQueryUseCase
import io.github.ticketing.coupon.application.port.provided.CursorPage
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.application.port.provided.MyCouponView
import io.github.ticketing.coupon.application.port.required.CouponQueryPort
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class CouponQueryService(private val queries: CouponQueryPort, private val clock: Clock) : CouponQueryUseCase {
    override fun adminList(cursor: Long?, size: Int): CursorPage<AdminCouponView> = queries.findDefinitions(cursor, size)

    override fun adminGet(definitionId: Long): AdminCouponView = queries.findDefinition(definitionId) ?: throw couponNotFound()

    override fun adminIssued(definitionId: Long, cursor: Long?, size: Int, status: IssuedCouponStatus?): CursorPage<IssuedCouponView> {
        if (queries.findDefinition(definitionId) == null) throw couponNotFound()
        return queries.findIssued(definitionId, cursor, size, status, clock.instant())
    }

    override fun claimable(memberId: Long): List<ClaimableCouponView> = queries.findClaimable(memberId, clock.instant())

    override fun myCoupons(memberId: Long): List<MyCouponView> = queries.findMyCoupons(memberId, clock.instant())
}
