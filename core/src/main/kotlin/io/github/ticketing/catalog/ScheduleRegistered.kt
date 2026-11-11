package io.github.ticketing.catalog

import java.math.BigDecimal
import java.time.Instant

/**
 * 회차가 등록되었음을 알린다. booking이 이 이벤트로 좌석·가격 사본을 만든다.
 * 다른 모듈은 catalog를 직접 조회할 수 없으므로 판매에 필요한 정보를 모두 담는다.
 * 추후 Kafka로 외부화되므로 직렬화 가능한 단순 타입만 쓴다.
 */
data class ScheduleRegistered(
    val scheduleId: Long,
    val performanceId: Long,
    val performanceTitle: String,
    val organizerId: Long,
    val startsAt: Instant,
    val seats: List<ScheduleSeat>,
)

/** [grade]는 등급 이름(VIP, R, S, A), [label]은 표시용 문자열이다(예: "A구역 3열 5번"). */
data class ScheduleSeat(val seatId: Long, val grade: String, val label: String, val price: BigDecimal)
