package io.github.ticketing.catalog

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.catalog.domain.Performance
import io.github.ticketing.catalog.domain.Schedule
import io.github.ticketing.catalog.domain.SeatGrade
import io.github.ticketing.catalog.domain.Venue
import io.github.ticketing.catalog.domain.VenueSeat
import io.github.ticketing.catalog.repository.PerformanceRepository
import io.github.ticketing.catalog.repository.ScheduleRepository
import io.github.ticketing.catalog.repository.VenueRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant

class CatalogRepositoryTests : IntegrationTestSupport() {

    @Autowired
    lateinit var venues: VenueRepository

    @Autowired
    lateinit var performances: PerformanceRepository

    @Autowired
    lateinit var schedules: ScheduleRepository

    @Autowired
    lateinit var tx: TransactionTemplate

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun venue() = Venue("공연장", listOf(VenueSeat("A", "1", 1, SeatGrade.VIP), VenueSeat("A", "1", 2, SeatGrade.R)), now)

    @Test
    fun `공연장과 좌석을 저장하고 조회한다`() {
        val saved = venues.save(venue())

        val seats =
            tx.execute {
                venues.findById(requireNotNull(saved.id)).orElseThrow().seats.map { it.key to it.grade }
            }

        assertThat(seats).hasSize(2)
        assertThat(seats).contains(venue().seats[0].key to SeatGrade.VIP)
    }

    @Test
    fun `공연과 등급별 가격, 회차를 저장하고 조회한다`() {
        val venueId = requireNotNull(venues.save(venue()).id)
        val saved =
            performances.save(
                Performance(7, "공연", "설명", venueId, mapOf(SeatGrade.VIP to BigDecimal(150000), SeatGrade.R to BigDecimal(100000)), now),
            )

        val prices = tx.execute { performances.findById(requireNotNull(saved.id)).orElseThrow().prices.toMap() }

        assertThat(prices).containsEntry(SeatGrade.VIP, BigDecimal(150000))

        val schedule = schedules.save(Schedule(requireNotNull(saved.id), now.plusSeconds(3600), now))

        assertThat(schedules.findById(requireNotNull(schedule.id))).isPresent
    }

    @Test
    fun `같은 공연에 같은 시작 시각의 회차는 저장할 수 없다`() {
        val venueId = requireNotNull(venues.save(venue()).id)
        val performanceId =
            requireNotNull(performances.save(Performance(7, "공연", "설명", venueId, mapOf(SeatGrade.VIP to BigDecimal.ONE), now)).id)
        schedules.save(Schedule(performanceId, now, now))

        assertThatThrownBy { schedules.saveAndFlush(Schedule(performanceId, now, now)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `DB는 같은 공연장의 중복 좌석 키를 거부한다`() {
        val venueId = requireNotNull(venues.save(venue()).id)

        assertThatThrownBy {
            tx.executeWithoutResult {
                val loadedVenue = venues.findById(venueId).orElseThrow()

                // 도메인 검증을 우회해 DB 제약만 검증한다
                val seatsField = Venue::class.java.getDeclaredField("_seats").apply { isAccessible = true }

                @Suppress("UNCHECKED_CAST")
                (seatsField.get(loadedVenue) as MutableList<VenueSeat>).add(VenueSeat("A", "1", 1, SeatGrade.S))

                venues.flush()
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
