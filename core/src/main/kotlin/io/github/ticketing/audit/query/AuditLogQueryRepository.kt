package io.github.ticketing.audit.query

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.audit.tables.references.ADMIN_ACTION
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset

@Repository
class AuditLogQueryRepository(private val dsl: DSLContext, private val json: JsonMapper) {

    /** ID 내림차순 커서 페이지. 다음 페이지 존재 여부를 알기 위해 1건 더 읽는다. */
    fun find(cursor: Long?, size: Int, action: String?, actorId: Long?, from: Instant?, to: Instant?): AuditLogPage {
        val conditions = listOfNotNull(
            cursor?.let { ADMIN_ACTION.ID.lt(it) },
            action?.takeIf { it.isNotBlank() }?.let { ADMIN_ACTION.ACTION.eq(it) },
            actorId?.let { ADMIN_ACTION.ACTOR_ID.eq(it) },
            from?.let { ADMIN_ACTION.OCCURRED_AT.ge(it.atOffset(ZoneOffset.UTC)) },
            to?.let { ADMIN_ACTION.OCCURRED_AT.lt(it.atOffset(ZoneOffset.UTC)) },
        )

        val rows = dsl.select(
            ADMIN_ACTION.ID,
            ADMIN_ACTION.ACTOR_ID,
            ADMIN_ACTION.ACTION,
            ADMIN_ACTION.TARGET_TYPE,
            ADMIN_ACTION.TARGET_ID,
            ADMIN_ACTION.DETAIL,
            ADMIN_ACTION.OCCURRED_AT,
        )
            .from(ADMIN_ACTION)
            .where(DSL.and(conditions))
            .orderBy(ADMIN_ACTION.ID.desc())
            .limit(size + 1)
            .fetch {
                AuditLogItem(
                    id = requireNotNull(it[ADMIN_ACTION.ID]),
                    actorId = it[ADMIN_ACTION.ACTOR_ID],
                    action = requireNotNull(it[ADMIN_ACTION.ACTION]),
                    targetType = requireNotNull(it[ADMIN_ACTION.TARGET_TYPE]),
                    targetId = requireNotNull(it[ADMIN_ACTION.TARGET_ID]),
                    detail = json.readValue(requireNotNull(it[ADMIN_ACTION.DETAIL]).data(), Map::class.java),
                    occurredAt = requireNotNull(it[ADMIN_ACTION.OCCURRED_AT]).toInstant(),
                )
            }

        val items = rows.take(size)
        return AuditLogPage(items, if (rows.size > size) items.last().id else null)
    }

    data class AuditLogItem(
        val id: Long,
        val actorId: Long?,
        val action: String,
        val targetType: String,
        val targetId: String,
        val detail: Map<*, *>,
        val occurredAt: Instant,
    )

    data class AuditLogPage(val items: List<AuditLogItem>, val nextCursor: Long?)
}
