package io.github.ticketing.booking.adapter.required.jpa

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.application.port.required.ScheduleSeatCommandPort
import io.github.ticketing.booking.domain.ScheduleCopy
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.Session
import org.hibernate.SessionEventListener
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant

class ScheduleSeatPersistenceAdapterTests(
    private val port: ScheduleSeatCommandPort,
    private val tx: TransactionTemplate,
    private val emf: EntityManagerFactory,
    private val jdbc: JdbcTemplate,
) : IntegrationTestSupport() {

    private val now = Instant.parse("2026-11-10T10:00:00Z")

    @Test
    fun `사본을 저장하면 좌석을 조회할 수 있고 두 번째 저장은 건너뛴다`() {
        val id = ScheduleId(System.nanoTime())
        val schedule = ScheduleCopy(id, 1, "공연", 2, now, now)
        val seats = (1L..3L).map { ScheduleSeatCopy(id, SeatId(it), "VIP", "A구역 $it", BigDecimal(150000)) }

        val firstSaved = tx.execute { port.saveIfAbsent(schedule, seats) }
        val secondSaved = tx.execute { port.saveIfAbsent(schedule, seats) }

        assertThat(firstSaved).isTrue()
        assertThat(secondSaved).isFalse()

        val found = tx.execute { port.findSeats(id, listOf(SeatId(1), SeatId(3), SeatId(99))) }

        assertThat(found.map { it.seatId.value }).containsExactlyInAnyOrder(1L, 3L)
        assertThat(found.first().price).isEqualByComparingTo("150000")
    }

    @Test
    fun `좌석 저장은 사전 SELECT 없이 INSERT만 실행한다`() {
        val id = ScheduleId(System.nanoTime())
        val schedule = ScheduleCopy(id, 1, "공연", 2, now, now)
        val seats = (1L..3L).map { ScheduleSeatCopy(id, SeatId(it), "VIP", "A구역 $it", BigDecimal(150000)) }
        var preparedStatements = 0

        tx.executeWithoutResult {
            val entityManager = requireNotNull(EntityManagerFactoryUtils.getTransactionalEntityManager(emf))
            entityManager.unwrap(Session::class.java).addEventListeners(object : SessionEventListener {
                override fun jdbcPrepareStatementStart() {
                    preparedStatements++
                }
            })
            port.saveIfAbsent(schedule, seats)
        }

        assertThat(preparedStatements).isEqualTo(3)
        val saved = jdbc.queryForList("select seat_id from booking.schedule_seat where schedule_id = ?", Long::class.java, id.value)
        assertThat(saved).containsExactlyInAnyOrderElementsOf(seats.map { it.seatId.value })
    }
}
