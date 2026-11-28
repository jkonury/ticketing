package io.github.ticketing.booking.adapter.provided.web

import io.github.ticketing.EventCollector
import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingForceCancelled
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.application.port.required.HoldResult
import io.github.ticketing.booking.application.port.required.SeatHoldPort
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import io.github.ticketing.coupon.CouponReleaseRequested
import io.github.ticketing.payment.RefundRequested
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.test.web.servlet.assertj.MockMvcTester
import ticketing.jooq.booking.tables.references.BOOKING_SEAT
import ticketing.jooq.booking.tables.references.SOLD_SEAT
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import ticketing.jooq.booking.tables.references.BOOKING_ as BOOKING

class AdminBookingIntegrationTests(
    private val mockMvcTester: MockMvcTester,
    private val encoder: JwtEncoder,
    private val dsl: DSLContext,
    private val register: RegisterScheduleSeatsUseCase,
    private val collector: EventCollector,
    private val holds: SeatHoldPort,
    private val clock: MutableClock,
) : IntegrationTestSupport() {

    @Test
    fun `관리자는 예매 목록을 조회한다`() {
        assertThat(mockMvcTester.get().uri(PATH).header("Authorization", bearer("ADMIN")))
            .hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.items").asArray().isNotNull()
    }

    @Test
    fun `회원과 회차와 상태와 생성 기간으로 거르고 ID 내림차순으로 페이지를 이동한다`() {
        val memberId = uniqueId()
        val scheduleId = schedule()
        val differentScheduleId = schedule()
        val from = Instant.parse("2040-01-01T00:00:00Z")
        val to = from.plusSeconds(3600)
        val first = booking(memberId, scheduleId, "PENDING", from)
        val second = booking(memberId, scheduleId, "PENDING", to)
        booking(memberId, scheduleId, "PENDING", from.minusSeconds(1))
        booking(memberId, scheduleId, "PENDING", to.plusSeconds(1))
        booking(memberId, scheduleId, "EXPIRED", from)
        booking(memberId, differentScheduleId, "PENDING", from)
        booking(memberId + 1, scheduleId, "PENDING", from)
        val admin = bearer("ADMIN")

        val page = mockMvcTester.get().uri(PATH)
            .param("memberId", "$memberId").param("scheduleId", "$scheduleId").param("status", "PENDING")
            .param("createdFrom", from.toString()).param("createdTo", to.toString()).param("size", "1")
            .header("Authorization", admin)

        assertThat(page).hasStatus(HttpStatus.OK).bodyJson().apply {
            extractingPath("$.items.length()").isEqualTo(1)
            extractingPath("$.items[0].bookingId").convertTo(Long::class.java).isEqualTo(second)
            extractingPath("$.items[0].memberId").convertTo(Long::class.java).isEqualTo(memberId)
            extractingPath("$.items[0].scheduleId").convertTo(Long::class.java).isEqualTo(scheduleId)
            extractingPath("$.nextCursor").convertTo(Long::class.java).isEqualTo(second)
        }
        assertThat(page.param("cursor", "$second")).hasStatus(HttpStatus.OK).bodyJson().apply {
            extractingPath("$.items.length()").isEqualTo(1)
            extractingPath("$.items[0].bookingId").convertTo(Long::class.java).isEqualTo(first)
            extractingPath("$.nextCursor").isNull()
        }
    }

    @Test
    fun `공연 ID로 여러 회차의 예매를 함께 거른다`() {
        val performanceId = uniqueId()
        val memberId = uniqueId()
        val now = clock.instant()
        val first = booking(memberId, schedule(performanceId), "PENDING", now)
        val second = booking(memberId, schedule(performanceId), "CONFIRMED", now)
        booking(memberId, schedule(), "PENDING", now)

        assertThat(mockMvcTester.get().uri(PATH).param("performanceId", "$performanceId").header("Authorization", bearer("ADMIN")))
            .hasStatus(HttpStatus.OK).bodyJson().apply {
                extractingPath("$.items.length()").isEqualTo(2)
                extractingPath("$.items[0].bookingId").convertTo(Long::class.java).isEqualTo(second)
                extractingPath("$.items[1].bookingId").convertTo(Long::class.java).isEqualTo(first)
            }
        assertThat(mockMvcTester.get().uri(PATH).param("performanceId", "0").header("Authorization", bearer("ADMIN")))
            .hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `일반 회원과 주최자는 관리자 예매 목록에 접근할 수 없다`() {
        for (role in listOf("USER", "ORGANIZER")) {
            assertThat(mockMvcTester.get().uri(PATH).header("Authorization", bearer(role)))
                .hasStatus(HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `관리자는 다른 회원 예매의 좌석과 적용 쿠폰 및 결제 예정액을 조회한다`() {
        val memberId = uniqueId()
        val scheduleId = schedule()
        val createdAt = Instant.parse("2040-01-01T00:00:00Z")
        val id = booking(memberId, scheduleId, "PENDING", createdAt)
        dsl.update(BOOKING).set(BOOKING.COUPON_ISSUED_ID, 42L)
            .set(BOOKING.COUPON_STATE, "RESERVED").set(BOOKING.DISCOUNT_AMOUNT, 30L).where(BOOKING.ID.eq(id)).execute()

        assertThat(mockMvcTester.get().uri("$PATH/$id").header("Authorization", bearer("ADMIN")))
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.memberId").convertTo(Long::class.java).isEqualTo(memberId)
                extractingPath("$.scheduleId").convertTo(Long::class.java).isEqualTo(scheduleId)
                extractingPath("$.status").isEqualTo("PENDING")
                extractingPath("$.seats[0].label").isEqualTo("A1")
                extractingPath("$.seats[0].price").isEqualTo(100)
                extractingPath("$.listAmount").isEqualTo(100)
                extractingPath("$.discountAmount").isEqualTo(30)
                extractingPath("$.payableAmount").isEqualTo(70)
                extractingPath("$.coupon.issuedCouponId").isEqualTo(42)
                extractingPath("$.coupon.state").isEqualTo("RESERVED")
            }
        for (role in listOf("USER", "ORGANIZER")) {
            assertThat(mockMvcTester.get().uri("$PATH/$id").header("Authorization", bearer(role)))
                .hasStatus(HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `관리자가 다른 회원의 미결제 예매를 강제 취소한다`() {
        val memberId = uniqueId()
        val scheduleId = ScheduleId(schedule())
        val id = booking(memberId, scheduleId.value, "PENDING", clock.instant())
        val owner = HoldToken(requireNotNull(dsl.select(BOOKING.HOLD_TOKEN).from(BOOKING).where(BOOKING.ID.eq(id)).fetchSingle().value1()))
        val seat = listOf(SeatId(1))
        dsl.update(BOOKING).set(BOOKING.COUPON_ISSUED_ID, 42L)
            .set(BOOKING.COUPON_STATE, "RESERVED").set(BOOKING.DISCOUNT_AMOUNT, 30L).where(BOOKING.ID.eq(id)).execute()
        assertThat(holds.hold(scheduleId, seat, owner, Booking.HOLD_DURATION, clock.instant())).isEqualTo(HoldResult.Held)

        assertThat(cancellation(id, "ADMIN", """{"reason":"  중복 예매  "}""")).hasStatus(HttpStatus.OK)

        val saved = dsl.selectFrom(BOOKING).where(BOOKING.ID.eq(id)).fetchSingle()
        assertThat(saved.status).isEqualTo("CANCELLED")
        assertThat(saved.cancelReason).isEqualTo("ADMIN_CANCELLED")
        assertThat(mockMvcTester.get().uri("$PATH/$id").header("Authorization", bearer("ADMIN")))
            .hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.cancelReason").isEqualTo("ADMIN_CANCELLED")
        assertThat(holds.hold(scheduleId, seat, HoldToken("next-owner"), Booking.HOLD_DURATION, clock.instant())).isEqualTo(HoldResult.Held)
        assertThat(collector.of(CouponReleaseRequested::class.java) { it.orderId == id }).hasSize(1)
        assertThat(collector.of(RefundRequested::class.java) { it.orderId == id }).isEmpty()
        val cancelled = collector.of(BookingCancelled::class.java) { it.bookingId == id }.single()
        assertThat(cancelled.memberId).isEqualTo(memberId)
        assertThat(cancelled.refundRequired).isFalse()
        val forced = collector.of(BookingForceCancelled::class.java) { it.bookingId == id }.single()
        assertThat(forced.actorId).isEqualTo(1L)
        assertThat(forced.reason).isEqualTo("중복 예매")
    }

    @Test
    fun `확정 예매를 강제 취소하면 좌석을 반환하고 환불을 요청한다`() {
        val scheduleId = schedule()
        val id = booking(uniqueId(), scheduleId, "CONFIRMED", clock.instant())
        dsl.insertInto(SOLD_SEAT).set(SOLD_SEAT.SCHEDULE_ID, scheduleId).set(SOLD_SEAT.SEAT_ID, 1L)
            .set(SOLD_SEAT.BOOKING_ID, id).set(SOLD_SEAT.SOLD_AT, clock.instant().atOffset(ZoneOffset.UTC)).execute()

        assertThat(cancellation(id, "ADMIN", """{"reason":"운영자 취소"}"""))
            .hasStatus(HttpStatus.OK).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED")

        assertThat(dsl.fetchExists(SOLD_SEAT, SOLD_SEAT.BOOKING_ID.eq(id))).isFalse()
        val refund = collector.of(RefundRequested::class.java) { it.orderId == id }.single()
        assertThat(refund.reason).isEqualTo("ADMIN_CANCELLED")
        val cancelled = collector.of(BookingCancelled::class.java) { it.bookingId == id }.single()
        assertThat(cancelled.reason).isEqualTo("ADMIN_CANCELLED")
        assertThat(cancelled.refundRequired).isTrue()
        assertThat(collector.of(CouponReleaseRequested::class.java) { it.orderId == id }).isEmpty()
    }

    @Test
    fun `만료되거나 취소된 예매의 강제 취소를 거부한다`() {
        val scheduleId = schedule()
        for (status in listOf("EXPIRED", "CANCELLED")) {
            val id = booking(uniqueId(), scheduleId, status, clock.instant())

            assertThat(cancellation(id, "ADMIN", """{"reason":"운영자 취소"}"""))
                .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("INVALID_BOOKING_STATE")
        }
    }

    @Test
    fun `사유가 없거나 공백이거나 200자를 넘으면 강제 취소를 거부한다`() {
        val id = booking(uniqueId(), schedule(), "PENDING", clock.instant())

        for (body in listOf("{}", """{"reason":"  "}""", """{"reason":"${"가".repeat(201)}"}""")) {
            assertThat(cancellation(id, "ADMIN", body)).hasStatus(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `일반 회원과 주최자는 예매를 강제 취소할 수 없다`() {
        val id = booking(uniqueId(), schedule(), "PENDING", clock.instant())

        for (role in listOf("USER", "ORGANIZER")) {
            assertThat(cancellation(id, role, """{"reason":"운영자 취소"}""")).hasStatus(HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `없는 예매의 강제 취소는 404다`() {
        assertThat(cancellation(Long.MAX_VALUE, "ADMIN", """{"reason":"운영자 취소"}"""))
            .hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code").isEqualTo("BOOKING_NOT_FOUND")
    }

    @Test
    fun `없는 예매 상세는 404다`() {
        assertThat(mockMvcTester.get().uri("$PATH/${Long.MAX_VALUE}").header("Authorization", bearer("ADMIN")))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("BOOKING_NOT_FOUND")
    }

    private fun cancellation(id: Long, role: String, body: String) = mockMvcTester.post().uri("$PATH/$id/cancellation")
        .header("Authorization", bearer(role)).contentType("application/json").content(body)

    private fun schedule(performanceId: Long = uniqueId()): Long {
        val id = uniqueId()
        register.register(
            RegisterScheduleSeatsCommand(
                id,
                performanceId,
                "공연",
                uniqueId(),
                Instant.parse("2040-02-01T00:00:00Z"),
                listOf(RegisterScheduleSeatsCommand.SeatInfo(1, "R", "A1", BigDecimal(100))),
            ),
        )
        return id
    }

    private fun booking(memberId: Long, scheduleId: Long, status: String, createdAt: Instant): Long {
        val record = dsl.insertInto(BOOKING)
            .set(BOOKING.MEMBER_ID, memberId).set(BOOKING.SCHEDULE_ID, scheduleId).set(BOOKING.STATUS, status)
            .set(BOOKING.TOTAL_AMOUNT, 100L).set(BOOKING.HOLD_TOKEN, UUID.randomUUID().toString())
            .set(BOOKING.IDEMPOTENCY_KEY, UUID.randomUUID().toString()).set(BOOKING.VERSION, 0L)
            .set(BOOKING.EXPIRES_AT, createdAt.plusSeconds(3600).atOffset(ZoneOffset.UTC))
            .set(BOOKING.CREATED_AT, createdAt.atOffset(ZoneOffset.UTC))
            .returning(BOOKING.ID).fetchOne()
        val id = requireNotNull(record?.id)
        dsl.insertInto(BOOKING_SEAT).set(BOOKING_SEAT.BOOKING_ID, id)
            .set(BOOKING_SEAT.SEAT_ID, 1L).set(BOOKING_SEAT.PRICE, 100L).execute()
        return id
    }

    private fun uniqueId(): Long = UUID.randomUUID().mostSignificantBits.ushr(16)

    private fun bearer(role: String): String {
        val claims = JwtClaimsSet.builder().subject("1").claim("role", role)
            .expiresAt(Instant.now().plusSeconds(60)).build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return "Bearer ${encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue}"
    }

    companion object {
        private const val PATH = "/api/v1/admin/bookings"
    }
}
