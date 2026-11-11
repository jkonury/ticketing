package io.github.ticketing.catalog.service

import io.github.ticketing.catalog.ScheduleRegistered
import io.github.ticketing.catalog.domain.Performance
import io.github.ticketing.catalog.domain.Schedule
import io.github.ticketing.catalog.domain.SeatGrade
import io.github.ticketing.catalog.domain.Venue
import io.github.ticketing.catalog.repository.PerformanceRepository
import io.github.ticketing.catalog.repository.ScheduleRepository
import io.github.ticketing.catalog.repository.VenueRepository
import io.github.ticketing.catalog.service.CatalogRegistrationService.PriceSpec
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterPerformanceCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterScheduleCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterVenueCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.SeatSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class CatalogRegistrationServiceTests {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val venues = FakeVenues()
    private val performances = FakePerformances()
    private val schedules = FakeSchedules()
    private val events = mutableListOf<Any>()
    private val service = CatalogRegistrationService(
        venues,
        performances,
        schedules,
        ApplicationEventPublisher { events.add(it) },
        Clock.fixed(now, ZoneOffset.UTC),
    )
    private val seat = SeatSpec(" A ", " 1 ", 1, SeatGrade.VIP)
    private val price = PriceSpec(SeatGrade.VIP, BigDecimal("10000"))

    @Test
    fun `공연장과 공연을 등록할 때 입력을 정리하고 좌석 등급의 가격을 저장한다`() {
        val venue = service.registerVenue(RegisterVenueCommand(" 공연장 ", listOf(seat)))
        val performance = service.registerPerformance(performanceCommand(requireNotNull(venue.id)))

        assertThat(venue.name).isEqualTo("공연장")
        assertThat(venue.seats.single().section).isEqualTo("A")
        assertThat(performance.title).isEqualTo("공연")
        assertThat(performance.description).isEqualTo("설명")
        assertThat(performance.prices).containsEntry(SeatGrade.VIP, BigDecimal("10000"))
        assertThat(performance.createdAt).isEqualTo(now)
    }

    @Test
    fun `좌석 중복과 가격 중복 또는 누락 및 없는 공연장을 거절한다`() {
        assertThatThrownBy { service.registerVenue(RegisterVenueCommand("공연장", listOf(seat, seat))) }
            .isInstanceOf(DuplicateVenueSeatException::class.java)
        val venue = service.registerVenue(RegisterVenueCommand("공연장", listOf(seat)))
        val command = performanceCommand(requireNotNull(venue.id))

        assertThatThrownBy { service.registerPerformance(command.copy(prices = listOf(price, price))) }
            .isInstanceOf(DuplicatePriceGradeException::class.java)
        assertThatThrownBy { service.registerPerformance(command.copy(prices = listOf(PriceSpec(SeatGrade.R, BigDecimal.ONE)))) }
            .isInstanceOf(PriceMissingForGradeException::class.java)
        assertThatThrownBy { service.registerPerformance(command.copy(venueId = 999)) }.isInstanceOf(VenueNotFoundException::class.java)
    }

    @Test
    fun `회차 등록은 좌석과 가격을 담은 이벤트를 발행한다`() {
        val command = prepareSchedule()

        val schedule = service.registerSchedule(command)

        val event = events.single() as ScheduleRegistered
        assertThat(event.scheduleId).isEqualTo(schedule.id)
        assertThat(event.performanceId).isEqualTo(command.performanceId)
        assertThat(event.organizerId).isEqualTo(command.requesterId)
        assertThat(event.performanceTitle).isEqualTo("공연")
        assertThat(event.startsAt).isEqualTo(command.startsAt)
        assertThat(event.seats.single().label).isEqualTo("A구역 1열 1번")
        assertThat(event.seats.single().grade).isEqualTo("VIP")
        assertThat(event.seats.single().price).isEqualByComparingTo("10000")
        assertThat(event.seats.single().seatId).isEqualTo(1L)
    }

    @Test
    fun `소유자가 아니거나 공연이 없으면 회차와 이벤트를 만들지 않는다`() {
        val command = prepareSchedule()

        assertThatThrownBy {
            service.registerSchedule(command.copy(requesterId = 2))
        }.isInstanceOf(NotPerformanceOwnerException::class.java)
        assertThatThrownBy {
            service.registerSchedule(command.copy(performanceId = 999))
        }.isInstanceOf(PerformanceNotFoundException::class.java)
        assertThat(events).isEmpty()
        assertThat(schedules.values).isEmpty()
    }

    @Test
    fun `중복 회차와 저장 중 경합은 같은 예외로 변환하고 이벤트를 추가하지 않는다`() {
        val command = prepareSchedule()
        service.registerSchedule(command)

        assertThatThrownBy { service.registerSchedule(command) }.isInstanceOf(DuplicateScheduleException::class.java)
        schedules.failSave = true
        assertThatThrownBy { service.registerSchedule(command.copy(startsAt = now.plusSeconds(60))) }
            .isInstanceOf(DuplicateScheduleException::class.java)
            .hasCauseInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(events).hasSize(1)
    }

    private fun performanceCommand(venueId: Long) = RegisterPerformanceCommand(1, " 공연 ", " 설명 ", venueId, listOf(price))

    private fun prepareSchedule(): RegisterScheduleCommand {
        val venue = service.registerVenue(RegisterVenueCommand("공연장", listOf(seat)))
        val performance = service.registerPerformance(performanceCommand(requireNotNull(venue.id)))
        return RegisterScheduleCommand(1, requireNotNull(performance.id), now.plusSeconds(3600))
    }
}

private class FakeVenues : VenueRepository {
    private val values = mutableListOf<Venue>()

    override fun findById(id: Long): Optional<Venue> = Optional.ofNullable(values.find { it.id == id })

    override fun <S : Venue> save(entity: S): S {
        entity.id = values.size.toLong() + 1
        entity.seats.forEachIndexed { index, seat -> seat.id = index.toLong() + 1 }
        values.add(entity)
        return entity
    }

    override fun existsByName(name: String): Boolean = values.any { it.name == name }
}

private class FakePerformances : PerformanceRepository {
    private val values = mutableListOf<Performance>()

    override fun findById(id: Long): Optional<Performance> = Optional.ofNullable(values.find { it.id == id })

    override fun <S : Performance> save(entity: S): S {
        entity.id = values.size.toLong() + 1
        values.add(entity)
        return entity
    }
}

private class FakeSchedules : ScheduleRepository {
    val values = mutableListOf<Schedule>()
    var failSave = false

    override fun <S : Schedule> saveAndFlush(entity: S): S {
        if (failSave) throw DataIntegrityViolationException("duplicate")
        entity.id = values.size.toLong() + 1
        values.add(entity)
        return entity
    }

    override fun existsByPerformanceIdAndStartsAt(performanceId: Long, startsAt: Instant): Boolean =
        values.any { it.performanceId == performanceId && it.startsAt == startsAt }
}
