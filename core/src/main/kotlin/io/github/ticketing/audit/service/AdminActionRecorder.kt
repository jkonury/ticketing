package io.github.ticketing.audit.service

import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Service
import ticketing.jooq.audit.tables.references.ADMIN_ACTION
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class AdminActionRecorder(private val dsl: DSLContext, private val json: JsonMapper) {

    /** sourceEventId가 이미 있으면 무시한다. 이벤트 재전달에 멱등하다. */
    fun record(
        sourceEventId: UUID,
        actorId: Long?,
        action: String,
        targetType: String,
        targetId: String,
        detail: Map<String, Any?>,
        occurredAt: Instant,
    ) {
        dsl.insertInto(ADMIN_ACTION)
            .set(ADMIN_ACTION.ACTOR_ID, actorId)
            .set(ADMIN_ACTION.ACTION, action)
            .set(ADMIN_ACTION.TARGET_TYPE, targetType)
            .set(ADMIN_ACTION.TARGET_ID, targetId)
            .set(ADMIN_ACTION.DETAIL, JSONB.valueOf(json.writeValueAsString(detail)))
            .set(ADMIN_ACTION.OCCURRED_AT, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
            .set(ADMIN_ACTION.SOURCE_EVENT_ID, sourceEventId)
            .onConflict(ADMIN_ACTION.SOURCE_EVENT_ID)
            .doNothing()
            .execute()
    }
}
