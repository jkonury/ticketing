package io.github.ticketing.coupon

import java.time.Instant
import java.util.UUID

/** 관리자가 쿠폰 정의를 만들었다. eventId는 소비자(audit)가 멱등 키로 쓴다. */
data class CouponDefinitionCreated(val eventId: UUID, val actorId: Long, val definitionId: Long, val name: String, val occurredAt: Instant)
