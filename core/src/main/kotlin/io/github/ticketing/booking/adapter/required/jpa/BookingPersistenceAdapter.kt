package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.booking.application.port.required.BookingCommandPort
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.BookingId
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import jakarta.persistence.EntityManager
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
internal class BookingPersistenceAdapter(
    private val bookings: BookingJpaRepository,
    private val em: EntityManager,
) : BookingCommandPort {
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

    override fun findByMemberAndIdempotencyKey(memberId: MemberId, key: String): Booking? =
        bookings.findWithSeatsByMemberIdAndIdempotencyKey(memberId.value, key)?.toDomain()

    override fun insertSoldSeats(bookingId: BookingId, scheduleId: ScheduleId, seatIds: List<SeatId>, now: Instant): Int =
        seatIds.sumOf { seatId ->
            em.createNativeQuery(
                """
                INSERT INTO booking.sold_seat (schedule_id, seat_id, booking_id, sold_at)
                VALUES (:scheduleId, :seatId, :bookingId, :soldAt)
                ON CONFLICT (schedule_id, seat_id) DO NOTHING
                """.trimIndent(),
            )
                .setParameter("scheduleId", scheduleId.value)
                .setParameter("seatId", seatId.value)
                .setParameter("bookingId", bookingId.value)
                .setParameter("soldAt", now)
                .executeUpdate()
        }

    override fun deleteSoldSeats(bookingId: BookingId) {
        em.createNativeQuery("DELETE FROM booking.sold_seat WHERE booking_id = :bookingId")
            .setParameter("bookingId", bookingId.value)
            .executeUpdate()
    }
}
