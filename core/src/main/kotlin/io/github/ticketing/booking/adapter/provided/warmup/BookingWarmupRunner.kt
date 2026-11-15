package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.booking.adapter.provided.web.BookingResponse
import io.github.ticketing.booking.adapter.provided.web.BookingSeatResponse
import io.github.ticketing.booking.adapter.provided.web.CreateBookingRequest
import io.github.ticketing.booking.application.service.BookingWarmupService
import io.github.ticketing.booking.application.service.WarmupPlan
import io.github.ticketing.booking.domain.BookedSeat
import io.github.ticketing.booking.domain.Booking
import io.github.ticketing.booking.domain.HoldToken
import io.github.ticketing.booking.domain.MemberId
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@ConfigurationProperties("ticketing.warmup")
data class WarmupProperties(
    val enabled: Boolean = false,
    /** 공유 자원을 쓰지 않는 요청 경로(Jackson·검증·도메인·jOOQ 렌더링) 반복 횟수. JIT 컴파일 임계를 넘길 만큼 크게 둔다. */
    val layer1Iterations: Int = 30_000,
    val layer2Rounds: Int = 30,
    val requestsPerRound: Int = 48,
    val losersPerWinner: Int = 15,
    val concurrency: Int = 3,
    val maxRequestsPerSecond: Int = 300,
    /** 계층 2의 시간 상한. 넘으면 중단하고 ready로 전환한다. */
    val timeLimit: Duration = DEFAULT_TIME_LIMIT,
)

private val DEFAULT_TIME_LIMIT: Duration = Duration.ofSeconds(40)

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("ticketing.warmup.enabled", havingValue = "true")
@EnableConfigurationProperties(WarmupProperties::class)
class BookingWarmupConfiguration

/** 예열이 끝나기 전에는 `/actuator/health`가 UP이 아니므로 부하 러너·오케스트레이터가 기다린다. */
@Component
@ConditionalOnProperty("ticketing.warmup.enabled", havingValue = "true")
class WarmupHealthIndicator : HealthIndicator {
    private val finished = AtomicBoolean(false)

    fun markFinished() = finished.set(true)

    override fun health(): Health =
        if (finished.get()) Health.up().build() else Health.outOfService().withDetail("warmup", "running").build()
}

/**
 * 기동 시 내부 예열. 외부 부하 입장에서는 readiness 전에 끝나는 기동 과정이다(ADR 0028).
 * 실패해도 기동을 막지 않는다. 시간 상한을 넘기면 중단하고 ready로 전환한다.
 */
@Component
@ConditionalOnProperty("ticketing.warmup.enabled", havingValue = "true")
class BookingWarmupRunner(
    private val properties: WarmupProperties,
    private val warmup: BookingWarmupService,
    private val mapper: JsonMapper,
    private val validator: Validator,
    private val health: WarmupHealthIndicator,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        val started = System.nanoTime()
        try {
            warmRequestPath(properties.layer1Iterations)
            warmup.warmQueryRendering(properties.layer1Iterations)
            val layer1Millis = elapsedMillis(started)
            val report = warmup.warmCreateBooking(
                WarmupPlan(
                    rounds = properties.layer2Rounds,
                    requestsPerRound = properties.requestsPerRound,
                    losersPerWinner = properties.losersPerWinner,
                    concurrency = properties.concurrency,
                    maxRequestsPerSecond = properties.maxRequestsPerSecond,
                    timeLimit = properties.timeLimit,
                ),
            )
            // 러너 스크립트가 env.txt에 옮겨 적는 줄이다. 형식을 바꾸면 scripts/loadtest/run.sh도 고친다
            log.info(
                "internal-warmup: layer1Iterations={} layer1Ms={} layer2Requests={} winners={} losers={} errors={} " +
                    "redisCommands={} dbStatements={} layer2Ms={} truncated={} totalMs={}",
                properties.layer1Iterations,
                layer1Millis,
                report.requests,
                report.winners,
                report.losers,
                report.errors,
                report.redisCommands,
                report.dbStatements,
                report.elapsed.toMillis(),
                report.truncatedByTimeLimit,
                elapsedMillis(started),
            )
        } catch (e: RuntimeException) {
            log.warn("내부 예열이 실패해 중단하고 ready로 전환한다.", e)
        } finally {
            health.markFinished()
        }
    }

    private fun elapsedMillis(started: Long) = Duration.ofNanos(System.nanoTime() - started).toMillis()

    // 컨트롤러가 거치는 역직렬화·Bean Validation·도메인 규칙·응답 직렬화·409 ProblemDetail 직렬화를 DB·Redis 없이 반복한다
    private fun warmRequestPath(iterations: Int) {
        val now = Instant.now()
        var sink = 0L
        repeat(iterations) { index ->
            val request = mapper.readValue(REQUEST_JSON, CreateBookingRequest::class.java)
            sink += validator.validate(request).size
            val seats = request.seatIds.map { BookedSeat(SeatId(it), PRICE) }
            val booking = Booking.create(
                MemberId(index.toLong()),
                ScheduleId(request.scheduleId),
                seats,
                HoldToken(UUID.randomUUID().toString()),
                now,
                "warmup-$index",
            )
            val response = BookingResponse(
                index.toLong(),
                booking.status.name,
                booking.totalPrice,
                booking.expiresAt,
                seats.map { BookingSeatResponse(it.seatId.value, "warmup-${it.seatId.value}", it.price) },
            )
            sink += mapper.writeValueAsString(response).length
            val conflict = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "이미 선점되었거나 판매된 좌석이 있습니다.")
            conflict.setProperty("code", "SEAT_ALREADY_HELD")
            conflict.setProperty("seatIds", request.seatIds)
            sink += mapper.writeValueAsString(conflict).length
        }
        check(sink > 0) { "예열 결과가 비었다" }
    }

    private companion object {
        const val REQUEST_JSON = """{"scheduleId":1,"seatIds":[101,102]}"""
        val PRICE: BigDecimal = BigDecimal.valueOf(50_000)
    }
}
