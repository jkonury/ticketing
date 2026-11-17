package io.github.ticketing.coupon.domain

import java.math.BigDecimal
import java.time.Instant

enum class IssuedCouponStatus { ISSUED, RESERVED, USED, REVOKED, EXPIRED }

/** 상태 전이 규칙만 담는다. 동시성 보호는 영속 계층의 조건부 UPDATE가 맡는다. */
class IssuedCoupon(
    val id: IssuedCouponId?,
    val definitionId: CouponDefinitionId,
    val memberId: MemberId,
    status: IssuedCouponStatus,
    orderId: OrderId?,
    discountAmount: BigDecimal?,
    reservedUntil: Instant?,
    val issuedAt: Instant,
    usedAt: Instant?,
    val version: Long,
) {
    var status: IssuedCouponStatus = status
        private set
    var orderId: OrderId? = orderId
        private set
    var discountAmount: BigDecimal? = discountAmount
        private set
    var reservedUntil: Instant? = reservedUntil
        private set
    var usedAt: Instant? = usedAt
        private set

    fun reserve(orderId: OrderId, discount: BigDecimal, reservedUntil: Instant) {
        requireStatus(IssuedCouponStatus.ISSUED)
        this.status = IssuedCouponStatus.RESERVED
        this.orderId = orderId
        this.discountAmount = discount
        this.reservedUntil = reservedUntil
    }

    /** 예약을 취소하고 ISSUED로 복원한다. */
    fun release(orderId: OrderId) {
        requireReservedBy(orderId)
        this.status = IssuedCouponStatus.ISSUED
        this.orderId = null
        this.discountAmount = null
        this.reservedUntil = null
    }

    fun confirmUse(orderId: OrderId, now: Instant) {
        requireReservedBy(orderId)
        this.status = IssuedCouponStatus.USED
        this.usedAt = now
    }

    fun revoke() {
        requireStatus(IssuedCouponStatus.ISSUED)
        this.status = IssuedCouponStatus.REVOKED
    }

    private fun requireStatus(expected: IssuedCouponStatus) {
        if (status != expected) throw InvalidCouponStateException(status)
    }

    private fun requireReservedBy(orderId: OrderId) {
        requireStatus(IssuedCouponStatus.RESERVED)
        if (this.orderId != orderId) throw CouponOrderMismatchException()
    }

    companion object {
        fun issue(definitionId: CouponDefinitionId, memberId: MemberId, now: Instant) =
            IssuedCoupon(null, definitionId, memberId, IssuedCouponStatus.ISSUED, null, null, null, now, null, 0)
    }
}
