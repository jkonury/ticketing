package io.github.ticketing.catalog.query

import io.github.ticketing.catalog.domain.PerformanceGenre
import io.github.ticketing.catalog.domain.PerformanceStatus
import java.math.BigDecimal
import java.time.Instant

// 조회 전용 DTO. jOOQ 결과를 직접 매핑하고 Redis에 JSON으로 캐시되므로 단순 타입만 쓴다.

data class PerformanceSummary(
    val id: Long,
    val title: String,
    val venueName: String,
    val firstStartsAt: Instant?,
    val genre: PerformanceGenre?,
    val posterKey: String?,
)

data class PerformancePage(val items: List<PerformanceSummary>, val nextCursor: Long?)

data class GradePrice(val grade: String, val price: BigDecimal)

data class ScheduleSummary(val id: Long, val startsAt: Instant)

data class ScheduleDetail(val id: Long, val performanceId: Long, val performanceTitle: String, val startsAt: Instant)

data class PerformanceDetail(
    val id: Long,
    val title: String,
    val description: String,
    val venueId: Long,
    val venueName: String,
    val prices: List<GradePrice>,
    val schedules: List<ScheduleSummary>,
    val genre: PerformanceGenre? = null,
    val posterKey: String? = null,
)

data class OwnedPerformanceDetail(val performance: PerformanceDetail, val status: PerformanceStatus)

/** 좌석 상태(선점·판매)는 담지 않는다. 상태는 booking과 realtime 소관이다. */
data class SeatView(val seatId: Long, val section: String, val row: String, val number: Int, val grade: String, val price: BigDecimal)

/** 캐시에 목록 그대로 넣으면 역직렬화 타입이 흐려지므로 감싸서 저장한다. 응답은 [seats] 배열이다. */
data class SeatLayout(val seats: List<SeatView>)
