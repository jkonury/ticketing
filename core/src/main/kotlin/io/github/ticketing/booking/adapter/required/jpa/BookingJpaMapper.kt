package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.booking.domain.AppliedCoupon
import io.github.ticketing.booking.domain.BookedSeat
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.CancelReason
import io.github.ticketing.booking.domain.CouponState
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import java.math.BigDecimal

internal fun Booking.toNewEntity(): BookingJpaEntity {
    val entity = BookingJpaEntity(
        memberId = memberId.value,
        scheduleId = scheduleId.value,
        status = status.name,
        totalAmount = listAmount,
        holdToken = holdToken.value,
        idempotencyKey = idempotencyKey,
        expiresAt = expiresAt,
        paymentRequestedAt = paymentRequestedAt,
        confirmedAt = confirmedAt,
        cancelledAt = cancelledAt,
        cancelReason = cancelReason?.name,
        createdAt = createdAt,
    )
    // @MapsId 키는 영속화 시점에 부모 id로 채워진다
    seats.forEach { entity.seats.add(BookingSeatJpaEntity(entity, it.seatId.value, it.price)) }
    return entity
}

/** 상태 전이로 바뀔 수 있는 필드만 반영한다. 좌석과 정가는 생성 후 불변이다. */
internal fun BookingJpaEntity.applyChanges(booking: Booking) {
    status = booking.status.name
    paymentRequestedAt = booking.paymentRequestedAt
    confirmedAt = booking.confirmedAt
    cancelledAt = booking.cancelledAt
    cancelReason = booking.cancelReason?.name
    val coupon = booking.appliedCoupon
    couponIssuedId = coupon?.issuedCouponId
    couponState = coupon?.state?.name
    discountAmount = coupon?.discountAmount ?: BigDecimal.ZERO
    couponRejectReason = coupon?.rejectReason
    couponAttemptId = booking.couponAttemptId
}

internal fun BookingJpaEntity.toDomain(): Booking = Booking.restore(
    id = BookingId(requireNotNull(id)),
    memberId = MemberId(memberId),
    scheduleId = ScheduleId(scheduleId),
    seats = seats.map { BookedSeat(SeatId(it.key.seatId), it.price) }.sortedBy { it.seatId.value },
    holdToken = HoldToken(holdToken),
    idempotencyKey = idempotencyKey,
    version = version,
    createdAt = createdAt,
    expiresAt = expiresAt,
    status = BookingStatus.valueOf(status),
    cancelReason = cancelReason?.let { CancelReason.valueOf(it) },
    paymentRequestedAt = paymentRequestedAt,
    confirmedAt = confirmedAt,
    cancelledAt = cancelledAt,
    couponAttemptId = couponAttemptId,
    appliedCoupon = couponIssuedId?.let {
        AppliedCoupon(
            it,
            CouponState.valueOf(requireNotNull(couponState)),
            discountAmount.takeIf { _ ->
                couponState == "RESERVED"
            },
            couponRejectReason,
            couponAttemptId,
        )
    },
)

internal fun ScheduleSeatCopy.toEntity() = ScheduleSeatCopyJpaEntity(
    ScheduleSeatKey(scheduleId.value, seatId.value),
    grade,
    label,
    price,
)

internal fun ScheduleSeatCopyJpaEntity.toDomain() = ScheduleSeatCopy(
    ScheduleId(key.scheduleId),
    SeatId(key.seatId),
    grade,
    label,
    price,
)
