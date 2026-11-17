package io.github.ticketing.coupon.adapter.required.jpa

import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.DefinitionStatus
import io.github.ticketing.coupon.domain.DiscountPolicy
import io.github.ticketing.coupon.domain.DiscountType
import io.github.ticketing.coupon.domain.IssueMode
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import io.github.ticketing.coupon.domain.MemberId
import io.github.ticketing.coupon.domain.OrderId
import io.github.ticketing.coupon.domain.PerformanceId

internal fun CouponDefinitionJpaEntity.toDomain() = CouponDefinition(
    id = id?.let(::CouponDefinitionId),
    name = name,
    description = description,
    discountPolicy = DiscountPolicy(
        type = DiscountType.valueOf(discountType),
        value = discountValue,
        maxDiscount = maxDiscount,
        minOrderAmount = minOrderAmount,
        performanceId = performanceId?.let(::PerformanceId),
        validFrom = validFrom,
        validUntil = validUntil,
    ),
    issueMode = IssueMode.valueOf(issueMode),
    totalQuantity = totalQuantity,
    status = DefinitionStatus.valueOf(status),
    issueStartedAt = issueStartedAt,
    createdAt = createdAt,
    version = version,
)

internal fun CouponDefinition.toNewEntity() = CouponDefinitionJpaEntity(
    name = name,
    description = description,
    discountType = discountPolicy.type.name,
    discountValue = discountPolicy.value,
    maxDiscount = discountPolicy.maxDiscount,
    minOrderAmount = discountPolicy.minOrderAmount,
    performanceId = discountPolicy.performanceId?.value,
    validFrom = discountPolicy.validFrom,
    validUntil = discountPolicy.validUntil,
    issueMode = issueMode.name,
    totalQuantity = totalQuantity,
    status = status.name,
    issueStartedAt = issueStartedAt,
    createdAt = createdAt,
).also { it.remainingQuantity = totalQuantity }

internal fun CouponDefinitionJpaEntity.applyChanges(definition: CouponDefinition) {
    name = definition.name
    description = definition.description
    discountType = definition.discountPolicy.type.name
    discountValue = definition.discountPolicy.value
    maxDiscount = definition.discountPolicy.maxDiscount
    minOrderAmount = definition.discountPolicy.minOrderAmount
    performanceId = definition.discountPolicy.performanceId?.value
    validFrom = definition.discountPolicy.validFrom
    validUntil = definition.discountPolicy.validUntil
    issueMode = definition.issueMode.name
    totalQuantity = definition.totalQuantity
    status = definition.status.name
    issueStartedAt = definition.issueStartedAt
}

internal fun IssuedCouponJpaEntity.toDomain() = IssuedCoupon(
    id = id?.let(::IssuedCouponId),
    definitionId = CouponDefinitionId(definitionId),
    memberId = MemberId(memberId),
    status = IssuedCouponStatus.valueOf(status),
    orderId = orderId?.let(::OrderId),
    discountAmount = discountAmount,
    reservedUntil = reservedUntil,
    issuedAt = issuedAt,
    usedAt = usedAt,
    version = version,
)
