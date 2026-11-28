package io.github.ticketing.member

import java.time.Instant
import java.util.UUID

data class MemberSuspensionChanged(
    val eventId: UUID,
    val actorId: Long,
    val memberId: Long,
    val from: String,
    val to: String,
    val reason: String?,
    val changedAt: Instant,
)
