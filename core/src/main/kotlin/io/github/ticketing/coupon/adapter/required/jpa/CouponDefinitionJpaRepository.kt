package io.github.ticketing.coupon.adapter.required.jpa

import org.springframework.data.jpa.repository.JpaRepository

internal interface CouponDefinitionJpaRepository : JpaRepository<CouponDefinitionJpaEntity, Long>
