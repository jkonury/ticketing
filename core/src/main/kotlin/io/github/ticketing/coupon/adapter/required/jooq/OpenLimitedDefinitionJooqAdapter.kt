package io.github.ticketing.coupon.adapter.required.jooq

import io.github.ticketing.coupon.application.port.required.OpenLimitedDefinitionQueryPort
import io.github.ticketing.coupon.domain.IssueMode
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.coupon.tables.references.COUPON_DEFINITION

@Repository
internal class OpenLimitedDefinitionJooqAdapter(private val dsl: DSLContext) : OpenLimitedDefinitionQueryPort {
    override fun findOpenLimitedIds(afterId: Long?, limit: Int): List<Long> = dsl.select(COUPON_DEFINITION.ID).from(COUPON_DEFINITION)
        .where(COUPON_DEFINITION.ISSUE_MODE.eq(IssueMode.LIMITED.name))
        .and(COUPON_DEFINITION.ISSUE_STARTED_AT.isNotNull)
        .and(if (afterId == null) DSL.noCondition() else COUPON_DEFINITION.ID.gt(afterId))
        .orderBy(COUPON_DEFINITION.ID.asc()).limit(limit).fetch { requireNotNull(it[COUPON_DEFINITION.ID]) }
}
