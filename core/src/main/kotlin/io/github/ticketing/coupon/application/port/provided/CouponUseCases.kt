package io.github.ticketing.coupon.application.port.provided

import io.github.ticketing.coupon.domain.DiscountType
import io.github.ticketing.coupon.domain.IssueMode
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import java.time.Instant

data class CreateCouponCommand(
    val actorId: Long,
    val name: String,
    val description: String?,
    val discountType: DiscountType,
    val discountValue: Long,
    val maxDiscount: Long?,
    val minOrderAmount: Long,
    val performanceId: Long?,
    val validFrom: Instant,
    val validUntil: Instant,
    val issueMode: IssueMode,
    val totalQuantity: Int?,
)

/** null인 필드는 바꾸지 않는다. */
data class UpdateCouponCommand(
    val actorId: Long,
    val definitionId: Long,
    val name: String?,
    val description: String?,
    val validUntil: Instant?,
    val discountType: DiscountType?,
    val discountValue: Long?,
    val maxDiscount: Long?,
    val minOrderAmount: Long?,
    val performanceId: Long?,
    val validFrom: Instant?,
    val issueMode: IssueMode?,
    val totalQuantity: Int?,
) {
    /** 발급 시작 후에는 바꿀 수 없는 할인 조건 필드가 하나라도 있는가. */
    val changesTerms: Boolean
        get() = listOf(discountType, discountValue, maxDiscount, minOrderAmount, performanceId, validFrom, issueMode, totalQuantity)
            .any { it != null }

    fun providedFields(): List<String> = listOf(
        "name" to name,
        "description" to description,
        "validUntil" to validUntil,
        "discountType" to discountType,
        "discountValue" to discountValue,
        "maxDiscount" to maxDiscount,
        "minOrderAmount" to minOrderAmount,
        "performanceId" to performanceId,
        "validFrom" to validFrom,
        "issueMode" to issueMode,
        "totalQuantity" to totalQuantity,
    ).filter { it.second != null }.map { it.first }
}

interface AdminCouponUseCase {
    fun create(command: CreateCouponCommand): CouponDefinitionView

    fun update(command: UpdateCouponCommand): CouponDefinitionView

    fun stop(actorId: Long, definitionId: Long): CouponDefinitionView

    fun grant(actorId: Long, definitionId: Long, memberIds: List<Long>): GrantResult

    fun revoke(actorId: Long, issuedCouponId: Long): IssuedCouponView
}

interface ClaimCouponUseCase {
    fun claim(memberId: Long, definitionId: Long): IssuedCouponView
}

interface CouponQueryUseCase {
    fun adminList(cursor: Long?, size: Int): CursorPage<AdminCouponView>

    fun adminGet(definitionId: Long): AdminCouponView

    fun adminIssued(definitionId: Long, cursor: Long?, size: Int, status: IssuedCouponStatus?): CursorPage<IssuedCouponView>

    fun claimable(memberId: Long): List<ClaimableCouponView>

    fun myCoupons(memberId: Long): List<MyCouponView>
}
