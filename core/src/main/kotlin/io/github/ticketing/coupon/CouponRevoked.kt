package io.github.ticketing.coupon

import java.time.Instant
import java.util.UUID

/** 관리자가 발급된 쿠폰을 회수했다. */
data class CouponRevoked(
    val eventId: UUID,
    val actorId: Long,
    val issuedCouponId: Long,
    val definitionId: Long,
    val memberId: Long,
    val occurredAt: Instant,
)
