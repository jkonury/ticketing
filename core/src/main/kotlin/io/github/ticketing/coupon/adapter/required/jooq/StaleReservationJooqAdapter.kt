package io.github.ticketing.coupon.adapter.required.jooq

import io.github.ticketing.coupon.application.port.required.StaleReservationQueryPort
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.coupon.tables.references.ISSUED_COUPON
import java.time.Instant
import java.time.ZoneOffset

@Repository
internal class StaleReservationJooqAdapter(private val dsl: DSLContext) : StaleReservationQueryPort {
    override fun findStaleReservationIds(cutoff: Instant, afterId: Long?, limit: Int): List<Long> =
        dsl.select(ISSUED_COUPON.ID).from(ISSUED_COUPON)
            .where(ISSUED_COUPON.STATUS.eq(IssuedCouponStatus.RESERVED.name))
            .and(ISSUED_COUPON.RESERVED_UNTIL.lt(cutoff.atOffset(ZoneOffset.UTC)))
            .and(if (afterId == null) DSL.noCondition() else ISSUED_COUPON.ID.gt(afterId))
            .orderBy(ISSUED_COUPON.ID.asc()).limit(limit).fetch { requireNotNull(it[ISSUED_COUPON.ID]) }
}
