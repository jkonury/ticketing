package io.github.ticketing.coupon

import java.time.Instant
import java.util.UUID

/** 관리자가 쿠폰 정의를 수정했다. changedFields는 요청에 담긴 필드 이름이다. */
data class CouponDefinitionUpdated(
    val eventId: UUID,
    val actorId: Long,
    val definitionId: Long,
    val changedFields: List<String>,
    val occurredAt: Instant,
)
