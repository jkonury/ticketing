package io.github.ticketing.coupon.adapter.required.jpa

import io.github.ticketing.coupon.application.port.required.IssueResult
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import io.github.ticketing.coupon.domain.MemberId
import io.github.ticketing.coupon.domain.OrderId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import ticketing.jooq.coupon.tables.references.ISSUED_COUPON
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

@Repository
internal class IssuedCouponPersistenceAdapter(private val issuedCoupons: IssuedCouponJpaRepository, private val dsl: DSLContext) :
    IssuedCouponCommandPort {
    override fun issue(coupon: IssuedCoupon): IssueResult {
        // JPA save의 유니크 위반 예외는 호출 트랜잭션을 rollback-only로 만들므로 ON CONFLICT로 결과만 받는다
        val insertedId = dsl.insertInto(ISSUED_COUPON)
            .set(ISSUED_COUPON.DEFINITION_ID, coupon.definitionId.value)
            .set(ISSUED_COUPON.MEMBER_ID, coupon.memberId.value)
            .set(ISSUED_COUPON.STATUS, coupon.status.name)
            .set(ISSUED_COUPON.ISSUED_AT, coupon.issuedAt.atOffset(ZoneOffset.UTC))
            .onConflict(ISSUED_COUPON.DEFINITION_ID, ISSUED_COUPON.MEMBER_ID)
            .doNothing()
            .returning(ISSUED_COUPON.ID)
            .fetchOne()
            ?.id ?: return IssueResult.AlreadyClaimed

        val saved = issuedCoupons.findById(insertedId).orElseThrow { IllegalStateException("발급한 쿠폰을 찾을 수 없다: $insertedId") }
        return IssueResult.Issued(saved.toDomain())
    }

    override fun findById(id: IssuedCouponId): IssuedCoupon? = issuedCoupons.findById(id.value).map { it.toDomain() }.orElse(null)

    override fun findByDefinitionAndMember(definitionId: CouponDefinitionId, memberId: MemberId): IssuedCoupon? =
        issuedCoupons.findByDefinitionIdAndMemberId(definitionId.value, memberId.value)?.toDomain()

    override fun reserveIfIssued(id: IssuedCouponId, orderId: OrderId, discountAmount: BigDecimal, reservedUntil: Instant): Boolean =
        issuedCoupons.reserveIfIssued(id.value, orderId.value, discountAmount, reservedUntil) == 1

    override fun releaseIfReservedBy(id: IssuedCouponId, orderId: OrderId): Boolean =
        issuedCoupons.releaseIfReservedBy(id.value, orderId.value) == 1

    override fun confirmIfReservedBy(id: IssuedCouponId, orderId: OrderId, now: Instant): Boolean =
        issuedCoupons.confirmIfReservedBy(id.value, orderId.value, now) == 1

    override fun forceUse(id: IssuedCouponId, orderId: OrderId, now: Instant): IssuedCouponStatus? {
        val previous = issuedCoupons.findById(id.value).map { IssuedCouponStatus.valueOf(it.status) }.orElse(null) ?: return null
        issuedCoupons.forceUse(id.value, orderId.value, now)
        return previous
    }

    override fun revokeIfIssued(id: IssuedCouponId): Boolean = issuedCoupons.revokeIfIssued(id.value) == 1
}
