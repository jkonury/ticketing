package io.github.ticketing.coupon.application.port.provided

import java.time.Instant

data class CouponDefinitionView(
    val id: Long,
    val name: String,
    val description: String?,
    val discountType: String,
    val discountValue: Long,
    val maxDiscount: Long?,
    val minOrderAmount: Long,
    val performanceId: Long?,
    val validFrom: Instant,
    val validUntil: Instant,
    val issueMode: String,
    val totalQuantity: Int?,
    val status: String,
    val issueStartedAt: Instant?,
)

data class CouponStats(val issued: Long, val reserved: Long, val used: Long, val revoked: Long)

data class AdminCouponView(val definition: CouponDefinitionView, val stats: CouponStats)

data class IssuedCouponView(
    val id: Long,
    val definitionId: Long,
    val memberId: Long,
    val status: String,
    val orderId: Long?,
    val discountAmount: Long?,
    val issuedAt: Instant,
    val usedAt: Instant?,
)

data class ClaimableCouponView(val definition: CouponDefinitionView, val claimed: Boolean)

data class MyCouponView(val coupon: IssuedCouponView, val definition: CouponDefinitionView)

data class CursorPage<T>(val items: List<T>, val nextCursor: Long?)

data class GrantResult(val granted: List<Long>, val alreadyClaimed: List<Long>)
