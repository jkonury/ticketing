package io.github.ticketing.coupon.application.port.required

import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId

interface CouponDefinitionCommandPort {
    fun save(definition: CouponDefinition): CouponDefinition

    fun findById(id: CouponDefinitionId): CouponDefinition?
}
