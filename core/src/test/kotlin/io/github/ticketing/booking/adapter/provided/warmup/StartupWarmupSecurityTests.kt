package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.TestPg
import io.github.ticketing.booking.adapter.provided.web.BookingController
import io.github.ticketing.expectStatus
import io.github.ticketing.payment.adapter.provided.warmup.PaymentWarmupWork
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.web.method.HandlerMethod
import ticketing.jooq.booking.tables.references.BOOKING_
import java.time.Duration

@TestPropertySource(properties = ["ticketing.startup-warmup.enabled=true", "ticketing.startup-warmup.local-iterations=0"])
internal class StartupWarmupSecurityTests(
    private val mockMvcTester: MockMvcTester,
    private val dsl: DSLContext,
    private val redis: StringRedisTemplate,
    private val availability: ApplicationAvailability,
    private val clock: MutableClock,
    private val jdbc: JdbcTemplate,
    private val testPg: TestPg,
    private val paymentWarmup: PaymentWarmupWork,
) : IntegrationTestSupport() {
    private val warmupRole = jwt().authorities(SimpleGrantedAuthority("ROLE_STARTUP_WARMUP"))

    // 자격은 컨텍스트당 한 번만 발급되므로 첫 테스트에서 받은 값을 공유한다.
    private fun secret(): String = synchronized(Companion) {
        issued ?: mockMvcTester.post().uri("/internal/startup-warmup/credential").with(warmupRole).exchange()
            .expectStatus(HttpStatus.OK)
            .response.contentAsString
            .let { requireNotNull(Regex("\"key\":\"([^\"]+)\"").find(it)).groupValues[1] }.also { issued = it }
    }

    private fun bookingRequest(key: String = WARMUP_WINNER_KEY, body: String = TEMPLATE): MockMvcTester.MockMvcRequestBuilder =
        mockMvcTester.post().uri("/api/v1/bookings").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)

    private fun warmupRequest(key: String = WARMUP_WINNER_KEY, body: String = TEMPLATE): MockMvcTester.MockMvcRequestBuilder =
        bookingRequest(key, body).with(warmupRole).header(STARTUP_WARMUP_HEADER, secret())

    @Test
    fun `자격 발급은 서버 역할만 가능하고 한 번만 가능하다`() {
        assertThat(mockMvcTester.post().uri("/internal/startup-warmup/credential")).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(
            mockMvcTester.post().uri("/internal/startup-warmup/credential")
                .with(jwt().authorities(SimpleGrantedAuthority("ROLE_USER"))),
        ).hasStatus(HttpStatus.FORBIDDEN)

        secret()

        assertThat(mockMvcTester.post().uri("/internal/startup-warmup/credential").with(warmupRole)).hasStatus(HttpStatus.CONFLICT)
    }

    @Test
    fun `예열 승자 요청은 실제 BookingController 핸들러를 거치고 데이터를 남기지 않는다`() {
        val before = dsl.fetchCount(BOOKING_)

        val result = warmupRequest(WARMUP_WINNER_KEY).exchange()
        assertThat(result)
            .hasStatus(HttpStatus.CREATED)
            .bodyJson().extractingPath("$.status").isEqualTo("PENDING")
        assertThat(result.response.getHeader(STARTUP_WARMUP_IO_HEADER)).matches("db=\\d+;redis=\\d+")

        assertThat((result.mvcResult.handler as HandlerMethod).beanType).isEqualTo(BookingController::class.java)
        assertThat(dsl.fetchCount(BOOKING_)).isEqualTo(before)
        assertThat(dsl.fetchCount(BOOKING_, BOOKING_.SCHEDULE_ID.lt(0))).isZero()
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
        assertThat(availability.readinessState.name).isEqualTo("ACCEPTING_TRAFFIC")
    }

    @Test
    fun `예열 패자 요청은 실제 핸들러가 선점 충돌 409로 응답한다`() {
        val result = warmupRequest(WARMUP_CONFLICT_KEY).exchange()
        assertThat(result)
            .hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.code").isEqualTo("SEAT_ALREADY_HELD")

        assertThat((result.mvcResult.handler as HandlerMethod).beanType).isEqualTo(BookingController::class.java)
        assertThat(dsl.fetchCount(BOOKING_, BOOKING_.SCHEDULE_ID.lt(0))).isZero()
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
    }

    @Test
    fun `위조된 자격은 거부한다`() {
        val real = secret()

        assertThat(bookingRequest().header(STARTUP_WARMUP_HEADER, real)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(bookingRequest().with(warmupRole).header(STARTUP_WARMUP_HEADER, "forged")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(
            bookingRequest().with(jwt().authorities(SimpleGrantedAuthority("ROLE_USER"))).header(STARTUP_WARMUP_HEADER, real),
        ).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(bookingRequest().with(warmupRole)).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `예열 자격은 예매 생성 외 경로와 실제 데이터 요청에 쓸 수 없다`() {
        val before = dsl.fetchCount(BOOKING_)

        assertThat(
            mockMvcTester.get().uri("/api/v1/bookings/me").with(warmupRole).header(STARTUP_WARMUP_HEADER, secret()),
        ).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(warmupRequest(body = """{"scheduleId":2,"seatIds":[1]}""")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(warmupRequest(body = """{"scheduleId":1,"seatIds":[2]}""")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(warmupRequest(key = "real-key")).hasStatus(HttpStatus.FORBIDDEN)

        assertThat(dsl.fetchCount(BOOKING_)).isEqualTo(before)
    }

    @Test
    fun `서버 역할 토큰만으로는 일반 예매 핸들러에 접근하지 못한다`() {
        assertThat(bookingRequest().with(warmupRole)).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `예열 기간이 지나면 유효한 자격도 거부한다`() {
        val real = secret()
        clock.now = clock.now.plus(Duration.ofMinutes(4))

        assertThat(bookingRequest().with(warmupRole).header(STARTUP_WARMUP_HEADER, real)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mockMvcTester.post().uri("/internal/startup-warmup/credential").with(warmupRole)).hasStatus(HttpStatus.CONFLICT)
    }

    private fun payment(id: String = "-1"): MockMvcTester.MockMvcRequestBuilder =
        mockMvcTester.post().uri("/api/v1/bookings/$id/payments").header("Idempotency-Key", WARMUP_PAYMENT_KEY)

    private fun counts(): List<Int> = listOf(
        "select count(*) from booking.booking",
        "select count(*) from booking.schedule",
        "select count(*) from booking.schedule_seat",
        "select count(*) from payment.payment",
        "select count(*) from payment.webhook_event",
        "select count(*) from event_publication",
    ).map { requireNotNull(jdbc.queryForObject(it, Int::class.java)) }

    @Test
    fun `예열 결제 요청은 실제 핸들러를 거치지만 결제 웹훅 이벤트 PG 요청을 남기지 않는다`() {
        val before = counts()
        val probesBefore = paymentWarmup.pgProbes

        val result = payment().with(warmupRole).header(STARTUP_WARMUP_HEADER, secret()).exchange()
        assertThat(result)
            .hasStatus(HttpStatus.ACCEPTED)
            .bodyJson().extractingPath("$.status").isEqualTo("PENDING")
        assertThat(result.response.getHeader(STARTUP_WARMUP_IO_HEADER)).matches("db=\\d+;redis=\\d+")

        assertThat((result.mvcResult.handler as HandlerMethod).beanType).isEqualTo(BookingController::class.java)
        assertThat(paymentWarmup.pgProbes).isEqualTo(probesBefore + 1)
        assertThat(counts()).isEqualTo(before)
        assertThat(testPg.requests).isEmpty()
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
    }

    @Test
    fun `예열 상세 조회는 실제 핸들러가 격리 데이터를 읽고 아무것도 남기지 않는다`() {
        val before = counts()

        val result = mockMvcTester.get().uri("/api/v1/bookings/-1").with(warmupRole).header(STARTUP_WARMUP_HEADER, secret()).exchange()
        assertThat(result)
            .hasStatus(HttpStatus.OK)
            .bodyJson().apply {
                extractingPath("$.status").isEqualTo("PENDING")
                extractingPath("$.performanceTitle").isEqualTo("warmup")
            }

        assertThat((result.mvcResult.handler as HandlerMethod).beanType).isEqualTo(BookingController::class.java)
        assertThat(counts()).isEqualTo(before)
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
    }

    @Test
    fun `예열 결제 요청과 상세 조회는 템플릿 예매 ID 외에는 거부한다`() {
        val real = secret()
        val before = counts()

        assertThat(payment("1").with(warmupRole).header(STARTUP_WARMUP_HEADER, real)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(payment("-2").with(warmupRole).header(STARTUP_WARMUP_HEADER, real)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mockMvcTester.get().uri("/api/v1/bookings/1").with(warmupRole).header(STARTUP_WARMUP_HEADER, real))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mockMvcTester.get().uri("/api/v1/bookings/-2").with(warmupRole).header(STARTUP_WARMUP_HEADER, real))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mockMvcTester.post().uri("/api/v1/bookings/-1/cancel").with(warmupRole).header(STARTUP_WARMUP_HEADER, real))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mockMvcTester.post().uri("/api/v1/payments/webhooks/fake-pg").with(warmupRole).header(STARTUP_WARMUP_HEADER, real))
            .hasStatus(HttpStatus.FORBIDDEN)

        assertThat(counts()).isEqualTo(before)
    }

    @Test
    fun `위조된 자격은 결제 요청과 상세 조회에도 거부한다`() {
        val real = secret()
        val user = jwt().authorities(SimpleGrantedAuthority("ROLE_USER"))

        assertThat(payment().header(STARTUP_WARMUP_HEADER, real)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(payment().with(warmupRole).header(STARTUP_WARMUP_HEADER, "forged")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(payment().with(user).header(STARTUP_WARMUP_HEADER, real)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mockMvcTester.get().uri("/api/v1/bookings/-1").header(STARTUP_WARMUP_HEADER, real)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(mockMvcTester.get().uri("/api/v1/bookings/-1").with(warmupRole).header(STARTUP_WARMUP_HEADER, "forged"))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mockMvcTester.get().uri("/api/v1/bookings/-1").with(user).header(STARTUP_WARMUP_HEADER, real))
            .hasStatus(HttpStatus.FORBIDDEN)
        // 헤더 없는 서버 역할 토큰은 일반 체인을 타므로 USER 권한이 없어 거부된다
        assertThat(payment().with(warmupRole)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(mockMvcTester.get().uri("/api/v1/bookings/-1").with(warmupRole)).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `예열 예매 생성은 좌석 1에서 4까지 연속인 템플릿만 허용한다`() {
        val before = counts()

        (1..4).forEach { count ->
            val seats = (1..count).joinToString(",")
            assertThat(warmupRequest(body = """{"scheduleId":1,"seatIds":[$seats]}""")).hasStatus(HttpStatus.CREATED)
        }
        assertThat(warmupRequest(body = """{"scheduleId":1,"seatIds":[1,3]}""")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(warmupRequest(body = """{"scheduleId":1,"seatIds":[2,1]}""")).hasStatus(HttpStatus.FORBIDDEN)

        assertThat(counts()).isEqualTo(before)
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
    }

    private companion object {
        const val WARMUP_PAYMENT_KEY = "startup-warmup:payment"
        const val TEMPLATE = """{"scheduleId":1,"seatIds":[1]}"""
        var issued: String? = null
    }
}
