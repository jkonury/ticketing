package io.github.ticketing.coupon

import java.time.Instant
import java.util.UUID

/** 관리자가 회원들에게 쿠폰을 직접 지급했다. memberIds는 실제로 새로 발급된 회원만 담는다. */
data class CouponsGranted(val eventId: UUID, val actorId: Long, val definitionId: Long, val memberIds: List<Long>, val occurredAt: Instant)
