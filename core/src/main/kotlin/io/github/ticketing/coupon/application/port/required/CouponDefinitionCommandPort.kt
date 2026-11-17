package io.github.ticketing.coupon.application.port.required

import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId

interface CouponDefinitionCommandPort {
    fun save(definition: CouponDefinition): CouponDefinition

    fun findById(id: CouponDefinitionId): CouponDefinition?

    /** `remaining_quantity > 0`일 때만 1 줄이는 조건부 UPDATE. 줄였으면 true, 소진이면 false. 호출 트랜잭션이 필요하다. */
    fun decrementRemainingIfAvailable(id: CouponDefinitionId): Boolean
}
