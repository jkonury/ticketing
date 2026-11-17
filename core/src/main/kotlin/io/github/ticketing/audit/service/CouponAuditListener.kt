package io.github.ticketing.audit.service

import io.github.ticketing.coupon.CouponDefinitionCreated
import io.github.ticketing.coupon.CouponDefinitionStopped
import io.github.ticketing.coupon.CouponDefinitionUpdated
import io.github.ticketing.coupon.CouponRevoked
import io.github.ticketing.coupon.CouponsGranted
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class CouponAuditListener(private val recorder: AdminActionRecorder) {

    @ApplicationModuleListener
    fun on(event: CouponDefinitionCreated) {
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "COUPON_DEFINITION_CREATED",
            targetType = DEFINITION,
            targetId = event.definitionId.toString(),
            detail = mapOf("name" to event.name),
            occurredAt = event.occurredAt,
        )
    }

    @ApplicationModuleListener
    fun on(event: CouponDefinitionUpdated) {
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "COUPON_DEFINITION_UPDATED",
            targetType = DEFINITION,
            targetId = event.definitionId.toString(),
            detail = mapOf("changedFields" to event.changedFields),
            occurredAt = event.occurredAt,
        )
    }

    @ApplicationModuleListener
    fun on(event: CouponDefinitionStopped) {
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "COUPON_DEFINITION_STOPPED",
            targetType = DEFINITION,
            targetId = event.definitionId.toString(),
            detail = emptyMap(),
            occurredAt = event.occurredAt,
        )
    }

    @ApplicationModuleListener
    fun on(event: CouponsGranted) {
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "COUPONS_GRANTED",
            targetType = DEFINITION,
            targetId = event.definitionId.toString(),
            detail = mapOf("memberIds" to event.memberIds),
            occurredAt = event.occurredAt,
        )
    }

    @ApplicationModuleListener
    fun on(event: CouponRevoked) {
        recorder.record(
            sourceEventId = event.eventId,
            actorId = event.actorId,
            action = "COUPON_REVOKED",
            targetType = "ISSUED_COUPON",
            targetId = event.issuedCouponId.toString(),
            detail = mapOf("definitionId" to event.definitionId, "memberId" to event.memberId),
            occurredAt = event.occurredAt,
        )
    }

    private companion object {
        private const val DEFINITION = "COUPON_DEFINITION"
    }
}
