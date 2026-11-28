package io.github.ticketing.catalog.service

import io.github.ticketing.catalog.ScheduleRegistered
import io.github.ticketing.catalog.ScheduleSeat
import io.github.ticketing.catalog.config.CatalogCaches
import io.github.ticketing.catalog.domain.Performance
import io.github.ticketing.catalog.domain.PerformanceGenre
import io.github.ticketing.catalog.domain.PerformanceStatus
import io.github.ticketing.catalog.domain.Schedule
import io.github.ticketing.catalog.domain.SeatGrade
import io.github.ticketing.catalog.domain.Venue
import io.github.ticketing.catalog.domain.VenueSeat
import io.github.ticketing.catalog.repository.PerformanceRepository
import io.github.ticketing.catalog.repository.ScheduleRepository
import io.github.ticketing.catalog.repository.VenueRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

@Service
class CatalogRegistrationService(
    private val venues: VenueRepository,
    private val performances: PerformanceRepository,
    private val schedules: ScheduleRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    @Transactional
    fun registerVenue(command: RegisterVenueCommand): Venue {
        val seats = command.seats.map { VenueSeat(it.section.trim(), it.rowLabel.trim(), it.seatNumber, it.grade) }

        // 도메인 불변식 위반(IllegalArgumentException)이 500으로 새지 않도록 중복을 먼저 400으로 변환한다
        if (seats.map { it.key }.toSet().size != seats.size) throw DuplicateVenueSeatException()
        return venues.save(Venue(command.name.trim(), seats, clock.instant()))
    }

    @Transactional
    @CacheEvict(CatalogCaches.PERFORMANCE, key = "#result.id")
    fun registerPerformance(command: RegisterPerformanceCommand): Performance {
        val venue = venues.findById(command.venueId).orElseThrow { VenueNotFoundException() }
        if (command.prices.map { it.grade }.toSet().size != command.prices.size) throw DuplicatePriceGradeException()

        val prices = command.prices.associate { it.grade to it.price }

        val missing = venue.seats.map { it.grade }.toSet() - prices.keys
        if (missing.isNotEmpty()) throw PriceMissingForGradeException(missing.sorted().map { it.name })

        return performances.save(
            Performance(
                command.organizerId,
                command.title.trim(),
                command.description.trim(),
                command.venueId,
                prices,
                clock.instant(),
                command.genre,
            ),
        )
    }

    // 상세에 회차 목록이 들어 있으므로 회차 등록 시 무효화한다. 캐시 매니저가 transactionAware라 커밋 뒤에 지운다
    @Transactional
    @CacheEvict(CatalogCaches.PERFORMANCE, key = "#command.performanceId")
    fun registerSchedule(command: RegisterScheduleCommand): Schedule {
        val performance = requireEditablePerformance(performances.findForUpdate(command.performanceId), command.requesterId)
        val performanceId = requireNotNull(performance.id)
        val schedule = saveSchedule(performanceId, command)

        val venue = venues.findById(performance.venueId).orElseThrow { VenueNotFoundException() }
        val seats = venue.seats.map { seat ->
            ScheduleSeat(
                seatId = requireNotNull(seat.id),
                grade = seat.grade.name,
                label = "${seat.section}구역 ${seat.rowLabel}열 ${seat.seatNumber}번",
                price = requireNotNull(performance.prices[seat.grade]) { "등록된 공연의 가격표에 ${seat.grade} 등급이 없다" },
            )
        }
        // 회차 저장과 같은 트랜잭션에서 발행한다. 이벤트 발행 레지스트리가 커밋 후 전달을 보장한다
        events.publishEvent(
            ScheduleRegistered(
                scheduleId = requireNotNull(schedule.id),
                performanceId = performanceId,
                performanceTitle = performance.title,
                organizerId = performance.organizerId,
                startsAt = schedule.startsAt,
                seats = seats,
            ),
        )
        return schedule
    }

    @Transactional(readOnly = true)
    fun posterKey(performanceId: Long, requesterId: Long): String? =
        requireEditablePerformance(performances.findById(performanceId).orElse(null), requesterId).posterKey

    @Transactional
    @CacheEvict(CatalogCaches.PERFORMANCE, key = "#performanceId")
    fun attachPoster(performanceId: Long, requesterId: Long, key: String) {
        val performance = requireEditablePerformance(performances.findForUpdate(performanceId), requesterId)
        if (performance.posterKey != null && performance.posterKey != key) throw PosterAlreadyAttachedException()
        performance.attachPoster(key)
    }

    private fun requireEditablePerformance(performance: Performance?, requesterId: Long): Performance {
        val error = when {
            performance == null -> PerformanceNotFoundException()

            performance.organizerId != requesterId -> NotPerformanceOwnerException()

            else -> when (performance.status) {
                PerformanceStatus.ACTIVE -> return performance
                PerformanceStatus.SUSPENDED -> PerformanceSuspendedException()
                PerformanceStatus.CANCELLED -> PerformanceCancelledException()
            }
        }
        throw error
    }

    private fun saveSchedule(performanceId: Long, command: RegisterScheduleCommand): Schedule {
        if (schedules.existsByPerformanceIdAndStartsAt(performanceId, command.startsAt)) throw DuplicateScheduleException()
        // exists 확인과 저장 사이에 같은 회차가 들어오면 유니크 제약이 막는다. 500 대신 409로 변환한다
        return try {
            schedules.saveAndFlush(Schedule(performanceId, command.startsAt, clock.instant()))
        } catch (e: DataIntegrityViolationException) {
            throw DuplicateScheduleException().also { it.initCause(e) }
        }
    }

    data class SeatSpec(val section: String, val rowLabel: String, val seatNumber: Int, val grade: SeatGrade)

    data class PriceSpec(val grade: SeatGrade, val price: BigDecimal)

    data class RegisterVenueCommand(val name: String, val seats: List<SeatSpec>)

    data class RegisterPerformanceCommand(
        val organizerId: Long,
        val title: String,
        val description: String,
        val venueId: Long,
        val prices: List<PriceSpec>,
        val genre: PerformanceGenre? = null,
    )

    data class RegisterScheduleCommand(val requesterId: Long, val performanceId: Long, val startsAt: Instant)
}
