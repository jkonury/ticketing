package io.github.ticketing.coupon.adapter.required.jooq

import io.github.ticketing.coupon.application.port.provided.AdminCouponView
import io.github.ticketing.coupon.application.port.provided.ClaimableCouponView
import io.github.ticketing.coupon.application.port.provided.CouponDefinitionView
import io.github.ticketing.coupon.application.port.provided.CouponStats
import io.github.ticketing.coupon.application.port.provided.CursorPage
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.application.port.provided.MyCouponView
import io.github.ticketing.coupon.application.port.required.CouponQueryPort
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.coupon.tables.references.COUPON_DEFINITION
import ticketing.jooq.coupon.tables.references.ISSUED_COUPON
import java.time.Instant
import java.time.ZoneOffset

@Repository
internal class CouponQueryJooqAdapter(private val dsl: DSLContext) : CouponQueryPort {

    override fun findDefinitions(cursor: Long?, size: Int): CursorPage<AdminCouponView> {
        val rows = dsl.selectFrom(COUPON_DEFINITION)
            .where(if (cursor != null) COUPON_DEFINITION.ID.lt(cursor) else DSL.noCondition())
            .orderBy(COUPON_DEFINITION.ID.desc())
            .limit(size + 1)
            .fetch()

        val page = rows.take(size)
        val stats = findStats(page.map { requireNotNull(it.id) })
        val items = page.map { AdminCouponView(it.toDefinitionView(), stats[it.id] ?: EMPTY_STATS) }
        return CursorPage(items, if (rows.size > size) items.last().definition.id else null)
    }

    override fun findDefinition(definitionId: Long): AdminCouponView? {
        val record = dsl.selectFrom(COUPON_DEFINITION).where(COUPON_DEFINITION.ID.eq(definitionId)).fetchOne() ?: return null
        return AdminCouponView(record.toDefinitionView(), findStats(listOf(definitionId))[definitionId] ?: EMPTY_STATS)
    }

    override fun findIssued(
        definitionId: Long,
        cursor: Long?,
        size: Int,
        status: IssuedCouponStatus?,
        now: Instant,
    ): CursorPage<IssuedCouponView> {
        val rows = dsl.select(ISSUED_COUPON.asterisk(), COUPON_DEFINITION.VALID_UNTIL)
            .from(ISSUED_COUPON)
            .join(COUPON_DEFINITION).on(COUPON_DEFINITION.ID.eq(ISSUED_COUPON.DEFINITION_ID))
            .where(ISSUED_COUPON.DEFINITION_ID.eq(definitionId))
            .and(if (cursor != null) ISSUED_COUPON.ID.lt(cursor) else DSL.noCondition())
            .and(statusCondition(status, now))
            .orderBy(ISSUED_COUPON.ID.desc())
            .limit(size + 1)
            .fetch()

        val items = rows.take(size).map { it.toIssuedView(now) }
        return CursorPage(items, if (rows.size > size) items.last().id else null)
    }

    override fun findIssuedById(issuedCouponId: Long, now: Instant): IssuedCouponView? =
        dsl.select(ISSUED_COUPON.asterisk(), COUPON_DEFINITION.VALID_UNTIL)
            .from(ISSUED_COUPON)
            .join(COUPON_DEFINITION).on(COUPON_DEFINITION.ID.eq(ISSUED_COUPON.DEFINITION_ID))
            .where(ISSUED_COUPON.ID.eq(issuedCouponId))
            .fetchOne()?.toIssuedView(now)

    override fun findClaimable(memberId: Long, now: Instant): List<ClaimableCouponView> {
        val at = now.atOffset(ZoneOffset.UTC)
        val claimed = DSL.exists(
            DSL.selectOne().from(ISSUED_COUPON)
                .where(ISSUED_COUPON.DEFINITION_ID.eq(COUPON_DEFINITION.ID)).and(ISSUED_COUPON.MEMBER_ID.eq(memberId)),
        )
        val claimedField = DSL.field(claimed)

        return dsl.select(COUPON_DEFINITION.asterisk(), claimedField)
            .from(COUPON_DEFINITION)
            .where(COUPON_DEFINITION.STATUS.eq("ACTIVE"))
            .and(COUPON_DEFINITION.ISSUE_MODE.`in`("UNLIMITED", "LIMITED"))
            .and(COUPON_DEFINITION.VALID_FROM.le(at))
            .and(COUPON_DEFINITION.VALID_UNTIL.gt(at))
            .orderBy(COUPON_DEFINITION.ID.desc())
            .fetch { ClaimableCouponView(it.toDefinitionView(), it[claimedField] == true) }
    }

    override fun findMyCoupons(memberId: Long, now: Instant): List<MyCouponView> =
        dsl.select(ISSUED_COUPON.asterisk(), COUPON_DEFINITION.asterisk())
            .from(ISSUED_COUPON)
            .join(COUPON_DEFINITION).on(COUPON_DEFINITION.ID.eq(ISSUED_COUPON.DEFINITION_ID))
            .where(ISSUED_COUPON.MEMBER_ID.eq(memberId))
            .orderBy(ISSUED_COUPON.ISSUED_AT.desc(), ISSUED_COUPON.ID.desc())
            .fetch { MyCouponView(it.toIssuedView(now), it.toDefinitionView()) }

    /** 정의별 상태 건수. 한 번의 그룹 조회로 페이지 전체를 채운다. */
    private fun findStats(definitionIds: List<Long>): Map<Long, CouponStats> {
        if (definitionIds.isEmpty()) return emptyMap()

        val count = DSL.count()
        return dsl.select(ISSUED_COUPON.DEFINITION_ID, ISSUED_COUPON.STATUS, count)
            .from(ISSUED_COUPON)
            .where(ISSUED_COUPON.DEFINITION_ID.`in`(definitionIds))
            .groupBy(ISSUED_COUPON.DEFINITION_ID, ISSUED_COUPON.STATUS)
            .fetch()
            .groupBy({
                requireNotNull(it[ISSUED_COUPON.DEFINITION_ID])
            }, { requireNotNull(it[ISSUED_COUPON.STATUS]) to it[count].toLong() })
            .mapValues { (_, counts) -> counts.toMap().toStats() }
    }

    /** EXPIRED는 저장 상태가 아니어도 ISSUED이고 유효 기간이 지난 쿠폰을 포함한다. */
    private fun statusCondition(status: IssuedCouponStatus?, now: Instant): Condition {
        val at = now.atOffset(ZoneOffset.UTC)
        return when (status) {
            null -> DSL.noCondition()

            IssuedCouponStatus.ISSUED -> ISSUED_COUPON.STATUS.eq("ISSUED").and(COUPON_DEFINITION.VALID_UNTIL.ge(at))

            IssuedCouponStatus.EXPIRED -> ISSUED_COUPON.STATUS.eq("EXPIRED")
                .or(ISSUED_COUPON.STATUS.eq("ISSUED").and(COUPON_DEFINITION.VALID_UNTIL.lt(at)))

            IssuedCouponStatus.RESERVED, IssuedCouponStatus.USED, IssuedCouponStatus.REVOKED -> ISSUED_COUPON.STATUS.eq(status.name)
        }
    }

    private fun Map<String, Long>.toStats() = CouponStats(
        issued = this["ISSUED"] ?: 0,
        reserved = this["RESERVED"] ?: 0,
        used = this["USED"] ?: 0,
        revoked = this["REVOKED"] ?: 0,
    )

    private fun Record.toDefinitionView() = CouponDefinitionView(
        id = requireNotNull(this[COUPON_DEFINITION.ID]),
        name = requireNotNull(this[COUPON_DEFINITION.NAME]),
        description = this[COUPON_DEFINITION.DESCRIPTION],
        discountType = requireNotNull(this[COUPON_DEFINITION.DISCOUNT_TYPE]),
        discountValue = requireNotNull(this[COUPON_DEFINITION.DISCOUNT_VALUE]),
        maxDiscount = this[COUPON_DEFINITION.MAX_DISCOUNT],
        minOrderAmount = requireNotNull(this[COUPON_DEFINITION.MIN_ORDER_AMOUNT]),
        performanceId = this[COUPON_DEFINITION.PERFORMANCE_ID],
        validFrom = requireNotNull(this[COUPON_DEFINITION.VALID_FROM]).toInstant(),
        validUntil = requireNotNull(this[COUPON_DEFINITION.VALID_UNTIL]).toInstant(),
        issueMode = requireNotNull(this[COUPON_DEFINITION.ISSUE_MODE]),
        totalQuantity = this[COUPON_DEFINITION.TOTAL_QUANTITY],
        status = requireNotNull(this[COUPON_DEFINITION.STATUS]),
        issueStartedAt = this[COUPON_DEFINITION.ISSUE_STARTED_AT]?.toInstant(),
    )

    private fun Record.toIssuedView(now: Instant): IssuedCouponView {
        val stored = requireNotNull(this[ISSUED_COUPON.STATUS])
        val validUntil = requireNotNull(this[COUPON_DEFINITION.VALID_UNTIL]).toInstant()
        return IssuedCouponView(
            id = requireNotNull(this[ISSUED_COUPON.ID]),
            definitionId = requireNotNull(this[ISSUED_COUPON.DEFINITION_ID]),
            memberId = requireNotNull(this[ISSUED_COUPON.MEMBER_ID]),
            status = if (stored == "ISSUED" && validUntil.isBefore(now)) "EXPIRED" else stored,
            orderId = this[ISSUED_COUPON.ORDER_ID],
            discountAmount = this[ISSUED_COUPON.DISCOUNT_AMOUNT],
            issuedAt = requireNotNull(this[ISSUED_COUPON.ISSUED_AT]).toInstant(),
            usedAt = this[ISSUED_COUPON.USED_AT]?.toInstant(),
        )
    }

    private companion object {
        private val EMPTY_STATS = CouponStats(0, 0, 0, 0)
    }
}
