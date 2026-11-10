package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
internal class ScheduleSeatPersistenceAdapter(private val em: EntityManager) : ScheduleSeatCommandPort {
    override fun saveIfAbsent(schedule: ScheduleCopy, seats: List<ScheduleSeatCopy>): Boolean {
        // 동시에 같은 이벤트가 와도 한쪽만 삽입되도록 회차 행을 먼저 ON CONFLICT로 선점한다
        val inserted = em.createNativeQuery(
            """
            INSERT INTO booking.schedule
                (schedule_id, performance_id, performance_title, organizer_id, starts_at, received_at)
            VALUES (:id, :performanceId, :title, :organizerId, :startsAt, :receivedAt)
            ON CONFLICT (schedule_id) DO NOTHING
            """.trimIndent(),
        )
            .setParameter("id", schedule.scheduleId.value)
            .setParameter("performanceId", schedule.performanceId)
            .setParameter("title", schedule.performanceTitle)
            .setParameter("organizerId", schedule.organizerId)
            .setParameter("startsAt", schedule.startsAt)
            .setParameter("receivedAt", schedule.receivedAt)
            .executeUpdate()
        if (inserted == 0) return false
        seats.forEach { em.persist(it.toEntity()) }
        return true
    }

    override fun findSeats(scheduleId: ScheduleId, seatIds: Collection<SeatId>): List<ScheduleSeatCopy> {
        if (seatIds.isEmpty()) return emptyList()
        return em.createQuery(
            "select s from ScheduleSeatCopyJpaEntity s where s.key.scheduleId = :scheduleId and s.key.seatId in :seatIds",
            ScheduleSeatCopyJpaEntity::class.java,
        )
            .setParameter("scheduleId", scheduleId.value)
            .setParameter("seatIds", seatIds.map { it.value })
            .resultList
            .map { it.toDomain() }
    }
}
