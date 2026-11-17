package io.github.ticketing.coupon.adapter.required.jooq

import io.github.ticketing.coupon.application.port.required.UseMismatchPort
import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import io.github.ticketing.coupon.domain.OrderId
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import ticketing.jooq.coupon.tables.references.USE_MISMATCH
import java.time.Instant
import java.time.ZoneOffset

@Repository
internal class UseMismatchJooqAdapter(private val dsl: DSLContext) : UseMismatchPort {
    override fun record(
        issuedCouponId: IssuedCouponId,
        orderId: OrderId,
        previousStatus: IssuedCouponStatus,
        detectedAt: Instant,
        detail: String?,
    ) {
        dsl.insertInto(USE_MISMATCH)
            .set(USE_MISMATCH.ISSUED_COUPON_ID, issuedCouponId.value)
            .set(USE_MISMATCH.ORDER_ID, orderId.value)
            .set(USE_MISMATCH.PREVIOUS_STATUS, previousStatus.name)
            .set(USE_MISMATCH.DETECTED_AT, detectedAt.atOffset(ZoneOffset.UTC))
            .set(USE_MISMATCH.DETAIL, detail)
            .execute()
    }
}
