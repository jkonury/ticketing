package io.github.ticketing.coupon.domain

@JvmInline
value class CouponDefinitionId(val value: Long)

@JvmInline
value class IssuedCouponId(val value: Long)

@JvmInline
value class MemberId(val value: Long)

@JvmInline
value class PerformanceId(val value: Long)

/** 주문(예매) 식별자. booking 모듈의 ID를 값으로만 보관한다. */
@JvmInline
value class OrderId(val value: Long)
