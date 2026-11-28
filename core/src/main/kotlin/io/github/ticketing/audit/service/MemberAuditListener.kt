package io.github.ticketing.audit.service

import io.github.ticketing.member.MemberRoleChanged
import io.github.ticketing.member.MemberSuspensionChanged
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class MemberAuditListener(private val recorder: AdminActionRecorder) {

    @ApplicationModuleListener
    fun on(event: MemberRoleChanged) {
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "MEMBER_ROLE_CHANGED",
            targetType = "MEMBER",
            targetId = event.memberId.toString(),
            detail = mapOf("from" to event.from, "to" to event.to),
            occurredAt = event.changedAt,
        )
    }

    @ApplicationModuleListener
    fun on(event: MemberSuspensionChanged) {
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "MEMBER_SUSPENSION_CHANGED",
            targetType = "MEMBER",
            targetId = event.memberId.toString(),
            detail = mapOf("from" to event.from, "to" to event.to, "reason" to event.reason),
            occurredAt = event.changedAt,
        )
    }
}
