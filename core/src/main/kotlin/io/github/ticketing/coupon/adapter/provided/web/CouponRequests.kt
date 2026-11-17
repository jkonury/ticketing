package io.github.ticketing.coupon.adapter.provided.web

import io.github.ticketing.coupon.application.port.provided.CreateCouponCommand
import io.github.ticketing.coupon.application.port.provided.UpdateCouponCommand
import io.github.ticketing.coupon.domain.DiscountType
import io.github.ticketing.coupon.domain.IssueMode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateCouponRequest(
    @field:NotBlank @field:Size(max = 100) val name: String?,
    @field:Size(max = 500) val description: String?,
    @field:NotNull val discountType: DiscountType?,
    @field:NotNull @field:Positive val discountValue: Long?,
    @field:Positive val maxDiscount: Long?,
    @field:NotNull @field:PositiveOrZero val minOrderAmount: Long?,
    @field:Positive val performanceId: Long?,
    @field:NotNull val validFrom: Instant?,
    @field:NotNull val validUntil: Instant?,
    @field:NotNull val issueMode: IssueMode?,
    @field:Positive val totalQuantity: Int?,
) {
    fun toCommand(actorId: Long) = CreateCouponCommand(
        actorId = actorId,
        name = requireNotNull(name),
        description = description,
        discountType = requireNotNull(discountType),
        discountValue = requireNotNull(discountValue),
        maxDiscount = maxDiscount,
        minOrderAmount = requireNotNull(minOrderAmount),
        performanceId = performanceId,
        validFrom = requireNotNull(validFrom),
        validUntil = requireNotNull(validUntil),
        issueMode = requireNotNull(issueMode),
        totalQuantity = totalQuantity,
    )
}

data class UpdateCouponRequest(
    @field:Size(min = 1, max = 100) val name: String? = null,
    @field:Size(max = 500) val description: String? = null,
    val validUntil: Instant? = null,
    val discountType: DiscountType? = null,
    @field:Positive val discountValue: Long? = null,
    @field:Positive val maxDiscount: Long? = null,
    @field:PositiveOrZero val minOrderAmount: Long? = null,
    @field:Positive val performanceId: Long? = null,
    val validFrom: Instant? = null,
    val issueMode: IssueMode? = null,
    @field:Positive val totalQuantity: Int? = null,
) {
    fun toCommand(actorId: Long, definitionId: Long) = UpdateCouponCommand(
        actorId, definitionId, name, description, validUntil, discountType, discountValue, maxDiscount,
        minOrderAmount, performanceId, validFrom, issueMode, totalQuantity,
    )
}

data class GrantCouponRequest(@field:NotEmpty @field:Size(max = 100) val memberIds: List<@Positive Long>?)
