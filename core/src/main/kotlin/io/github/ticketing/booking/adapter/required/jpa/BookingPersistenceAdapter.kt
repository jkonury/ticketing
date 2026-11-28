package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.BookingStatus
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import org.jooq.DSLContext
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Repository
import ticketing.jooq.booking.tables.references.SCHEDULE
import ticketing.jooq.booking.tables.references.SOLD_SEAT
import java.time.Instant
import java.time.ZoneOffset
import ticketing.jooq.booking.tables.references.BOOKING_ as BOOKING

@Repository
internal class BookingPersistenceAdapter(private val bookings: BookingJpaRepository, private val dsl: DSLContext) : BookingCommandPort {
    override fun save(booking: Booking): Booking {
        val id = booking.id
        if (id == null) return bookings.saveAndFlush(booking.toNewEntity()).toDomain()
        val entity = bookings.findWithSeatsById(id.value)
            ?: throw IllegalStateException("예매를 찾을 수 없다: ${id.value}")
        // 도메인이 읽은 시점의 버전과 다르면 그 사이 다른 트랜잭션이 갱신한 것이다
        if (entity.version != booking.version) {
            throw ObjectOptimisticLockingFailureException(BookingJpaEntity::class.java, id.value)
        }
        entity.applyChanges(booking)
        return bookings.saveAndFlush(entity).toDomain()
    }

    override fun findById(id: BookingId): Booking? = bookings.findWithSeatsById(id.value)?.toDomain()

    override fun findByMemberAndIdempotencyKey(memberId: MemberId, key: String): Booking? {
        // 신규 요청과 선점 경합의 패자는 예매가 없으므로 엔티티·좌석 컬렉션을 로드하지 않는다.
        if (!dsl.fetchExists(BOOKING, BOOKING.MEMBER_ID.eq(memberId.value).and(BOOKING.IDEMPOTENCY_KEY.eq(key)))) return null
        return bookings.findWithSeatsByMemberIdAndIdempotencyKey(memberId.value, key)?.toDomain()
    }

    override fun findActiveIds(performanceId: Long, afterId: Long, limit: Int): List<BookingId> = dsl.select(BOOKING.ID)
        .from(BOOKING)
        .join(SCHEDULE).on(SCHEDULE.SCHEDULE_ID.eq(BOOKING.SCHEDULE_ID))
        .where(SCHEDULE.PERFORMANCE_ID.eq(performanceId))
        .and(BOOKING.STATUS.`in`(BookingStatus.PENDING.name, BookingStatus.CONFIRMED.name))
        .and(BOOKING.ID.gt(afterId))
        .orderBy(BOOKING.ID.asc())
        .limit(limit)
        .fetch { BookingId(requireNotNull(it.value1())) }

    override fun insertSoldSeats(bookingId: BookingId, scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant): Int {
        if (seatIds.isEmpty()) return 0
        val soldAt = now.atOffset(ZoneOffset.UTC)
        // 좌석 하나라도 이미 팔렸으면 그 행만 건너뛰므로, 삽입된 행 수가 요청 수보다 작아진다
        return seatIds.fold(dsl.insertInto(SOLD_SEAT, SOLD_SEAT.SCHEDULE_ID, SOLD_SEAT.SEAT_ID, SOLD_SEAT.BOOKING_ID, SOLD_SEAT.SOLD_AT)) {
                step,
                seatId,
            ->
            step.values(scheduleId.value, seatId.value, bookingId.value, soldAt)
        }
            .onConflict(SOLD_SEAT.SCHEDULE_ID, SOLD_SEAT.SEAT_ID)
            .doNothing()
            .execute()
    }

    override fun deleteSoldSeats(bookingId: BookingId) {
        dsl.deleteFrom(SOLD_SEAT).where(SOLD_SEAT.BOOKING_ID.eq(bookingId.value)).execute()
    }
}
