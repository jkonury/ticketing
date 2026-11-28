package io.github.ticketing.payment.adapter.required.jooq

import io.github.ticketing.payment.application.port.provided.AdminPaymentView
import io.github.ticketing.payment.application.port.provided.PaymentStatusView
import io.github.ticketing.payment.application.port.required.PaymentQueryPort
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import ticketing.jooq.payment.tables.references.PAYMENT_ as PAYMENT

@Repository
class PaymentQueryStore(private val dsl: DSLContext) : PaymentQueryPort {
    override fun findMine(memberId: Long, bookingIds: List<Long>): List<PaymentStatusView> =
        dsl.select(PAYMENT.ORDER_ID, PAYMENT.STATUS, PAYMENT.FAILURE_REASON, PAYMENT.APPROVED_AT, PAYMENT.REFUNDED_AT)
            .from(PAYMENT)
            .where(PAYMENT.MEMBER_ID.eq(memberId).and(PAYMENT.ORDER_ID.`in`(bookingIds)))
            .orderBy(PAYMENT.ORDER_ID)
            .fetch { row ->
                PaymentStatusView(
                    requireNotNull(row[PAYMENT.ORDER_ID]),
                    requireNotNull(row[PAYMENT.STATUS]),
                    row[PAYMENT.FAILURE_REASON],
                    row[PAYMENT.APPROVED_AT]?.toInstant(),
                    row[PAYMENT.REFUNDED_AT]?.toInstant(),
                )
            }

    override fun findByBookingId(bookingId: Long): AdminPaymentView? = dsl.select(
        PAYMENT.ORDER_ID,
        PAYMENT.STATUS,
        PAYMENT.AMOUNT,
        PAYMENT.FAILURE_REASON,
        PAYMENT.REQUESTED_AT,
        PAYMENT.APPROVED_AT,
        PAYMENT.REFUND_REQUESTED_AT,
        PAYMENT.REFUNDED_AT,
    )
        .from(PAYMENT)
        .where(PAYMENT.ORDER_ID.eq(bookingId))
        .fetchOne { row ->
            AdminPaymentView(
                bookingId = requireNotNull(row[PAYMENT.ORDER_ID]),
                status = requireNotNull(row[PAYMENT.STATUS]),
                amount = BigDecimal.valueOf(requireNotNull(row[PAYMENT.AMOUNT])),
                failureReason = row[PAYMENT.FAILURE_REASON],
                requestedAt = requireNotNull(row[PAYMENT.REQUESTED_AT]).toInstant(),
                approvedAt = row[PAYMENT.APPROVED_AT]?.toInstant(),
                refundRequestedAt = row[PAYMENT.REFUND_REQUESTED_AT]?.toInstant(),
                refundedAt = row[PAYMENT.REFUNDED_AT]?.toInstant(),
            )
        }
}
