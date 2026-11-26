package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.application.port.provided.CouponDefinitionView
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import java.time.Instant

internal fun CouponDefinition.toView(): CouponDefinitionView = CouponDefinitionView(
    id = requireNotNull(id).value,
    name = name,
    description = description,
    discountType = discountPolicy.type,
    discountValue = discountPolicy.value.toLong(),
    maxDiscount = discountPolicy.maxDiscount?.toLong(),
    minOrderAmount = discountPolicy.minOrderAmount.toLong(),
    performanceId = discountPolicy.performanceId?.value,
    validFrom = discountPolicy.validFrom,
    validUntil = discountPolicy.validUntil,
    issueMode = issueMode,
    totalQuantity = totalQuantity,
    status = status,
    issueStartedAt = issueStartedAt,
)

internal fun IssuedCoupon.toView(validUntil: Instant, now: Instant): IssuedCouponView {
    val expired = status == IssuedCouponStatus.ISSUED && validUntil.isBefore(now)
    return IssuedCouponView(
        id = requireNotNull(id).value,
        definitionId = definitionId.value,
        memberId = memberId.value,
        status = if (expired) IssuedCouponStatus.EXPIRED else status,
        orderId = orderId?.value,
        discountAmount = discountAmount?.toLong(),
        issuedAt = issuedAt,
        usedAt = usedAt,
    )
}
