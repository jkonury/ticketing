package io.github.ticketing.coupon.adapter.required.jpa

import io.github.ticketing.coupon.application.port.required.ReservationAttempt
import io.github.ticketing.coupon.application.port.required.ReservationAttemptPort
import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.OrderId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import ticketing.jooq.coupon.tables.references.RESERVATION_ATTEMPT

@Repository
internal class ReservationAttemptPersistenceAdapter(private val dsl: DSLContext) : ReservationAttemptPort {
    override fun find(couponId: IssuedCouponId, orderId: OrderId): ReservationAttempt? = dsl.selectFrom(RESERVATION_ATTEMPT)
        .where(RESERVATION_ATTEMPT.ISSUED_COUPON_ID.eq(couponId.value))
        .and(RESERVATION_ATTEMPT.ORDER_ID.eq(orderId.value))
        .fetchOne { ReservationAttempt(requireNotNull(it.attemptId), it.rejectReason, it.releasedVersion) }

    override fun exists(couponId: IssuedCouponId): Boolean =
        dsl.fetchExists(dsl.selectOne().from(RESERVATION_ATTEMPT).where(RESERVATION_ATTEMPT.ISSUED_COUPON_ID.eq(couponId.value)))

    override fun save(couponId: IssuedCouponId, orderId: OrderId, attempt: ReservationAttempt) {
        dsl.insertInto(RESERVATION_ATTEMPT)
            .set(RESERVATION_ATTEMPT.ISSUED_COUPON_ID, couponId.value)
            .set(RESERVATION_ATTEMPT.ORDER_ID, orderId.value)
            .set(RESERVATION_ATTEMPT.ATTEMPT_ID, attempt.id)
            .set(RESERVATION_ATTEMPT.REJECT_REASON, attempt.rejectReason)
            .set(RESERVATION_ATTEMPT.RELEASED_VERSION, attempt.releasedVersion)
            .onConflict(RESERVATION_ATTEMPT.ISSUED_COUPON_ID, RESERVATION_ATTEMPT.ORDER_ID)
            .doUpdate()
            .set(RESERVATION_ATTEMPT.ATTEMPT_ID, attempt.id)
            .set(RESERVATION_ATTEMPT.REJECT_REASON, attempt.rejectReason)
            .set(RESERVATION_ATTEMPT.RELEASED_VERSION, attempt.releasedVersion)
            .where(RESERVATION_ATTEMPT.ATTEMPT_ID.le(attempt.id))
            .execute()
    }
}
