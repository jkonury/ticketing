package io.github.ticketing.catalog.seed

import io.github.ticketing.catalog.domain.SeatGrade
import io.github.ticketing.catalog.repository.VenueRepository
import io.github.ticketing.catalog.service.CatalogRegistrationService
import io.github.ticketing.catalog.service.CatalogRegistrationService.PriceSpec
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterPerformanceCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterScheduleCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterVenueCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.SeatSpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * 개발용 시드. SQL이 아니라 등록 서비스를 거쳐야 ScheduleRegistered가 발행되어 booking의 좌석 사본이 채워진다.
 * 같은 이름의 공연장이 있으면 그 공연장과 딸린 공연은 건너뛰므로 다시 실행해도 늘지 않는다.
 */
@Component
@Profile("seed")
class CatalogSeedRunner(
    private val registration: CatalogRegistrationService,
    private val venues: VenueRepository,
    private val clock: Clock,
    // 회원 존재 여부는 확인하지 않는다(member 모듈 비참조)
    @Value("\${ticketing.seed.organizer-id:1}") private val organizerId: Long,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        SEEDS.forEach { seed ->
            if (venues.existsByName(seed.venueName)) {
                log.info("시드 건너뜀: 공연장 {}이(가) 이미 있다", seed.venueName)
                return@forEach
            }
            val venue = registration.registerVenue(RegisterVenueCommand(seed.venueName, seats()))
            val venueId = requireNotNull(venue.id)
            seed.titles.forEachIndexed { index, title ->
                val performance = registration.registerPerformance(
                    RegisterPerformanceCommand(organizerId, title, "$title 시드 공연", venueId, PRICES.map { (g, p) -> PriceSpec(g, p) }),
                )
                val firstDay = clock.instant().plus(Duration.ofDays(7L + index)).truncatedTo(ChronoUnit.HOURS)
                repeat(SCHEDULES_PER_PERFORMANCE) { day ->
                    registration.registerSchedule(
                        RegisterScheduleCommand(organizerId, requireNotNull(performance.id), firstDay.plus(Duration.ofDays(day.toLong()))),
                    )
                }
            }
            log.info("시드 완료: 공연장 {}, 공연 {}편", seed.venueName, seed.titles.size)
        }
    }

    // 4구역 × 10열 × 20석. 앞 열일수록 높은 등급
    private fun seats(): List<SeatSpec> = SECTIONS.flatMap { section ->
        (1..ROWS).flatMap { row ->
            (1..SEATS_PER_ROW).map { number -> SeatSpec(section, row.toString(), number, gradeOf(row)) }
        }
    }

    private fun gradeOf(row: Int): SeatGrade = when {
        row <= VIP_LAST_ROW -> SeatGrade.VIP
        row <= R_LAST_ROW -> SeatGrade.R
        row <= S_LAST_ROW -> SeatGrade.S
        else -> SeatGrade.A
    }

    private data class Seed(val venueName: String, val titles: List<String>)

    private companion object {
        val SECTIONS = listOf("A", "B", "C", "D")
        const val ROWS = 10
        const val SEATS_PER_ROW = 20
        const val VIP_LAST_ROW = 2
        const val R_LAST_ROW = 5
        const val S_LAST_ROW = 8
        const val SCHEDULES_PER_PERFORMANCE = 3
        val PRICES = mapOf(
            SeatGrade.VIP to BigDecimal(150000),
            SeatGrade.R to BigDecimal(120000),
            SeatGrade.S to BigDecimal(90000),
            SeatGrade.A to BigDecimal(60000),
        )
        val SEEDS = listOf(
            Seed("시드 올림픽홀", listOf("시드 콘서트 A", "시드 뮤지컬 B")),
            Seed("시드 샤롯데씨어터", listOf("시드 연극 C")),
        )
    }
}
