package io.github.ticketing.booking.application.port.required

import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.ScheduleSeatCopy
import io.github.ticketing.booking.domain.SeatId

/**
 * 기동 시 내부 예열 전용 Redis 픽스처. 예열 회차는 실제 회차와 겹치지 않는 ID를 쓰고,
 * 모든 키가 `{scheduleId}` 해시 태그를 공유하므로 회차 단위로 만들고 지운다.
 */
interface WarmupFixturePort {
    /** 좌석 사본 캐시(ADR 0025)에 예열 좌석을 채워 예열 중 DB 사본 조회가 없게 한다. */
    fun seedSeatCopies(scheduleId: ScheduleId, seats: List<ScheduleSeatCopy>)

    /** 예열이 남긴 사본 캐시·선점·스냅샷·스트림 키를 모두 지운다. */
    fun cleanup(scheduleId: ScheduleId, seatIds: List<SeatId>)
}

/** DB 연결 없이 jOOQ 쿼리 객체 생성·SQL 렌더링 경로만 반복해 JIT 프로파일을 채운다. */
interface QueryRenderWarmupPort {
    fun renderBookingStatements(iterations: Int)
}
