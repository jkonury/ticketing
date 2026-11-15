package io.github.ticketing.booking.adapter.required.jooq

import io.github.ticketing.booking.application.port.required.QueryRenderWarmupPort
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import ticketing.jooq.booking.tables.references.SCHEDULE_SEAT
import ticketing.jooq.booking.tables.references.SOLD_SEAT
import java.time.OffsetDateTime
import ticketing.jooq.booking.tables.references.BOOKING_ as BOOKING

@Component
internal class JooqRenderWarmupAdapter(private val dsl: DSLContext) : QueryRenderWarmupPort {
    private companion object {
        // 요청당 최대 좌석 수(4)와 같은 크기의 IN 목록을 렌더링한다
        val REQUESTED_SEAT_IDS = listOf(1L, 2L, 3L, 4L)
    }

    // render는 연결을 잡지 않는다. 결과 SQL 문자열 길이를 합산해 JIT가 호출을 제거하지 못하게 한다
    override fun renderBookingStatements(iterations: Int) {
        var rendered = 0L
        repeat(iterations) { index ->
            val member = index.toLong()
            rendered += dsl.render(
                dsl.selectOne().whereExists(
                    dsl.selectOne().from(BOOKING).where(BOOKING.MEMBER_ID.eq(member).and(BOOKING.IDEMPOTENCY_KEY.eq("warmup-$index"))),
                ),
            ).length
            rendered += dsl.render(
                dsl.selectFrom(
                    SCHEDULE_SEAT,
                ).where(SCHEDULE_SEAT.SCHEDULE_ID.eq(member)).and(SCHEDULE_SEAT.SEAT_ID.`in`(REQUESTED_SEAT_IDS)),
            ).length
            rendered += dsl.render(
                dsl.insertInto(SOLD_SEAT, SOLD_SEAT.SCHEDULE_ID, SOLD_SEAT.SEAT_ID, SOLD_SEAT.BOOKING_ID, SOLD_SEAT.SOLD_AT)
                    .values(member, 1L, member, OffsetDateTime.MIN)
                    .values(member, 2L, member, OffsetDateTime.MIN)
                    .onConflict(SOLD_SEAT.SCHEDULE_ID, SOLD_SEAT.SEAT_ID)
                    .doNothing(),
            ).length
        }
        check(rendered > 0) { "jOOQ 렌더링 결과가 비었다" }
    }
}
