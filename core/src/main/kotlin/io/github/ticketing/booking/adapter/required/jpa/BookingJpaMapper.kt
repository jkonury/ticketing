package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.booking.domain.BookedSeat
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.CancelReason
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId

internal fun Booking.toNewEntity(): BookingJpaEntity {
    val entity = BookingJpaEntity(
        memberId = memberId.value,
        scheduleId = scheduleId.value,
        status = status.name,
        totalAmount = totalPrice,
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

/** 상태 전이로 바뀔 수 있는 필드만 반영한다. 좌석과 금액은 생성 후 불변이다. */
internal fun BookingJpaEntity.applyChanges(booking: Booking) {
    status = booking.status.name
    paymentRequestedAt = booking.paymentRequestedAt
    confirmedAt = booking.confirmedAt
    cancelledAt = booking.cancelledAt
    cancelReason = booking.cancelReason?.name
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
)

internal fun ScheduleSeatCopy.toEntity() = ScheduleSeatCopyJpaEntity(
    ScheduleSeatKey(scheduleId.value, seatId.value), grade, label, price,
)

internal fun ScheduleSeatCopyJpaEntity.toDomain() = ScheduleSeatCopy(
    ScheduleId(key.scheduleId), SeatId(key.seatId), grade, label, price,
)
