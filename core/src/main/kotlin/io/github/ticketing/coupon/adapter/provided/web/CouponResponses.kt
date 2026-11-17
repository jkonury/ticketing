package io.github.ticketing.coupon.adapter.provided.web

import com.fasterxml.jackson.annotation.JsonUnwrapped
import io.github.ticketing.coupon.application.port.provided.AdminCouponView
import io.github.ticketing.coupon.application.port.provided.ClaimableCouponView
import io.github.ticketing.coupon.application.port.provided.CouponDefinitionView
import io.github.ticketing.coupon.application.port.provided.CouponStats
import io.github.ticketing.coupon.application.port.provided.CursorPage
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.application.port.provided.MyCouponView

/** 응답 필드를 정의 응답 옆에 평평하게 펼친다. */
data class AdminCouponResponse(@get:JsonUnwrapped val definition: CouponDefinitionView, val stats: CouponStats)

data class ClaimableCouponResponse(@get:JsonUnwrapped val definition: CouponDefinitionView, val claimed: Boolean)

data class MyCouponResponse(@get:JsonUnwrapped val coupon: IssuedCouponView, val definition: CouponDefinitionView)

data class AdminCouponPageResponse(val items: List<AdminCouponResponse>, val nextCursor: Long?)

internal fun AdminCouponView.toResponse() = AdminCouponResponse(definition, stats)

internal fun CursorPage<AdminCouponView>.toResponse() = AdminCouponPageResponse(items.map { it.toResponse() }, nextCursor)

internal fun ClaimableCouponView.toResponse() = ClaimableCouponResponse(definition, claimed)

internal fun MyCouponView.toResponse() = MyCouponResponse(coupon, definition)
