package io.github.ticketing.coupon

import java.time.Instant
import java.util.UUID

/** 관리자가 쿠폰 정의의 신규 발급을 중지했다. */
data class CouponDefinitionStopped(val eventId: UUID, val actorId: Long, val definitionId: Long, val occurredAt: Instant)
