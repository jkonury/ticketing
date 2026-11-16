package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.MutableClock
import io.github.ticketing.TestPg
import io.github.ticketing.booking.adapter.provided.web.BookingController
import io.github.ticketing.payment.adapter.provided.warmup.PaymentWarmupWork
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.method.HandlerMethod
import ticketing.jooq.booking.tables.references.BOOKING_
import java.time.Duration

@TestPropertySource(properties = ["ticketing.startup-warmup.enabled=true", "ticketing.startup-warmup.local-iterations=0"])
internal class StartupWarmupSecurityTests(
    private val mvc: MockMvc,
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
        issued ?: mvc.perform(post("/internal/startup-warmup/credential").with(warmupRole))
            .andExpect(status().isOk).andReturn().response.contentAsString
            .let { requireNotNull(Regex("\"key\":\"([^\"]+)\"").find(it)).groupValues[1] }.also { issued = it }
    }

    private fun bookingRequest(key: String = WARMUP_WINNER_KEY, body: String = TEMPLATE): MockHttpServletRequestBuilder =
        post("/api/v1/bookings").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)

    private fun warmupRequest(key: String = WARMUP_WINNER_KEY, body: String = TEMPLATE) =
        bookingRequest(key, body).with(warmupRole).header(STARTUP_WARMUP_HEADER, secret())

    @Test
    fun `자격 발급은 서버 역할만 가능하고 한 번만 가능하다`() {
        mvc.perform(post("/internal/startup-warmup/credential")).andExpect(status().isUnauthorized)
        mvc.perform(post("/internal/startup-warmup/credential").with(jwt().authorities(SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().isForbidden)

        secret()

        mvc.perform(post("/internal/startup-warmup/credential").with(warmupRole)).andExpect(status().isConflict)
    }

    @Test
    fun `예열 승자 요청은 실제 BookingController 핸들러를 거치고 데이터를 남기지 않는다`() {
        val before = dsl.fetchCount(BOOKING_)

        val result = mvc.perform(warmupRequest(WARMUP_WINNER_KEY))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(header().string(STARTUP_WARMUP_IO_HEADER, org.hamcrest.Matchers.matchesPattern("db=\\d+;redis=\\d+")))
            .andReturn()

        assertThat((result.handler as HandlerMethod).beanType).isEqualTo(BookingController::class.java)
        assertThat(dsl.fetchCount(BOOKING_)).isEqualTo(before)
        assertThat(dsl.fetchCount(BOOKING_, BOOKING_.SCHEDULE_ID.lt(0))).isZero()
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
        assertThat(availability.readinessState.name).isEqualTo("ACCEPTING_TRAFFIC")
    }

    @Test
    fun `예열 패자 요청은 실제 핸들러가 선점 충돌 409로 응답한다`() {
        val result = mvc.perform(warmupRequest(WARMUP_CONFLICT_KEY))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SEAT_ALREADY_HELD"))
            .andReturn()

        assertThat((result.handler as HandlerMethod).beanType).isEqualTo(BookingController::class.java)
        assertThat(dsl.fetchCount(BOOKING_, BOOKING_.SCHEDULE_ID.lt(0))).isZero()
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
    }

    @Test
    fun `위조된 자격은 거부한다`() {
        val real = secret()

        mvc.perform(bookingRequest().header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isUnauthorized)
        mvc.perform(bookingRequest().with(warmupRole).header(STARTUP_WARMUP_HEADER, "forged")).andExpect(status().isForbidden)
        mvc.perform(bookingRequest().with(jwt().authorities(SimpleGrantedAuthority("ROLE_USER"))).header(STARTUP_WARMUP_HEADER, real))
            .andExpect(status().isForbidden)
        mvc.perform(bookingRequest().with(warmupRole)).andExpect(status().isForbidden)
    }

    @Test
    fun `예열 자격은 예매 생성 외 경로와 실제 데이터 요청에 쓸 수 없다`() {
        val before = dsl.fetchCount(BOOKING_)

        mvc.perform(get("/api/v1/bookings/me").with(warmupRole).header(STARTUP_WARMUP_HEADER, secret())).andExpect(status().isForbidden)
        mvc.perform(warmupRequest(body = """{"scheduleId":2,"seatIds":[1]}""")).andExpect(status().isForbidden)
        mvc.perform(warmupRequest(body = """{"scheduleId":1,"seatIds":[2]}""")).andExpect(status().isForbidden)
        mvc.perform(warmupRequest(key = "real-key")).andExpect(status().isForbidden)

        assertThat(dsl.fetchCount(BOOKING_)).isEqualTo(before)
    }

    @Test
    fun `서버 역할 토큰만으로는 일반 예매 핸들러에 접근하지 못한다`() {
        mvc.perform(bookingRequest().with(warmupRole)).andExpect(status().isForbidden)
    }

    @Test
    fun `예열 기간이 지나면 유효한 자격도 거부한다`() {
        val real = secret()
        clock.now = clock.now.plus(Duration.ofMinutes(4))

        mvc.perform(bookingRequest().with(warmupRole).header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isForbidden)
        mvc.perform(post("/internal/startup-warmup/credential").with(warmupRole)).andExpect(status().isConflict)
    }

    private fun payment(id: String = "-1") = post("/api/v1/bookings/$id/payments").header("Idempotency-Key", WARMUP_PAYMENT_KEY)

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

        val result = mvc.perform(payment().with(warmupRole).header(STARTUP_WARMUP_HEADER, secret()))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(header().string(STARTUP_WARMUP_IO_HEADER, org.hamcrest.Matchers.matchesPattern("db=\\d+;redis=\\d+")))
            .andReturn()

        assertThat((result.handler as HandlerMethod).beanType).isEqualTo(BookingController::class.java)
        assertThat(paymentWarmup.pgProbes).isEqualTo(probesBefore + 1)
        assertThat(counts()).isEqualTo(before)
        assertThat(testPg.requests).isEmpty()
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
    }

    @Test
    fun `예열 상세 조회는 실제 핸들러가 격리 데이터를 읽고 아무것도 남기지 않는다`() {
        val before = counts()

        val result = mvc.perform(get("/api/v1/bookings/-1").with(warmupRole).header(STARTUP_WARMUP_HEADER, secret()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.performanceTitle").value("warmup"))
            .andReturn()

        assertThat((result.handler as HandlerMethod).beanType).isEqualTo(BookingController::class.java)
        assertThat(counts()).isEqualTo(before)
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
    }

    @Test
    fun `예열 결제 요청과 상세 조회는 템플릿 예매 ID 외에는 거부한다`() {
        val real = secret()
        val before = counts()

        mvc.perform(payment("1").with(warmupRole).header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isForbidden)
        mvc.perform(payment("-2").with(warmupRole).header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/bookings/1").with(warmupRole).header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/bookings/-2").with(warmupRole).header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/bookings/-1/cancel").with(warmupRole).header(STARTUP_WARMUP_HEADER, real))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/payments/webhooks/fake-pg").with(warmupRole).header(STARTUP_WARMUP_HEADER, real))
            .andExpect(status().isForbidden)

        assertThat(counts()).isEqualTo(before)
    }

    @Test
    fun `위조된 자격은 결제 요청과 상세 조회에도 거부한다`() {
        val real = secret()
        val user = jwt().authorities(SimpleGrantedAuthority("ROLE_USER"))

        mvc.perform(payment().header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isUnauthorized)
        mvc.perform(payment().with(warmupRole).header(STARTUP_WARMUP_HEADER, "forged")).andExpect(status().isForbidden)
        mvc.perform(payment().with(user).header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/bookings/-1").header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/bookings/-1").with(warmupRole).header(STARTUP_WARMUP_HEADER, "forged")).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/bookings/-1").with(user).header(STARTUP_WARMUP_HEADER, real)).andExpect(status().isForbidden)
        // 헤더 없는 서버 역할 토큰은 일반 체인을 타므로 USER 권한이 없어 거부된다
        mvc.perform(payment().with(warmupRole)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/bookings/-1").with(warmupRole)).andExpect(status().isForbidden)
    }

    @Test
    fun `예열 예매 생성은 좌석 1에서 4까지 연속인 템플릿만 허용한다`() {
        val before = counts()

        (1..4).forEach { count ->
            val seats = (1..count).joinToString(",")
            mvc.perform(warmupRequest(body = """{"scheduleId":1,"seatIds":[$seats]}""")).andExpect(status().isCreated)
        }
        mvc.perform(warmupRequest(body = """{"scheduleId":1,"seatIds":[1,3]}""")).andExpect(status().isForbidden)
        mvc.perform(warmupRequest(body = """{"scheduleId":1,"seatIds":[2,1]}""")).andExpect(status().isForbidden)

        assertThat(counts()).isEqualTo(before)
        assertThat(redis.keys("booking:warmup:*")).isEmpty()
    }

    private companion object {
        const val WARMUP_PAYMENT_KEY = "startup-warmup:payment"
        const val TEMPLATE = """{"scheduleId":1,"seatIds":[1]}"""
        var issued: String? = null
    }
}
