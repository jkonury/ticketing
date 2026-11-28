package io.github.ticketing.booking.adapter.provided.web

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
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
    fun `없는 예매 상세는 404다`() {
        assertThat(mockMvcTester.get().uri("$PATH/${Long.MAX_VALUE}").header("Authorization", bearer("ADMIN")))
            .hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("BOOKING_NOT_FOUND")
    }

    private fun schedule(): Long {
        val id = uniqueId()
        register.register(
            RegisterScheduleSeatsCommand(
                id,
                uniqueId(),
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
