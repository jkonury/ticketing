package io.github.ticketing.loadtest

import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.bodyString
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.pause
import io.gatling.javaapi.core.Session
import io.gatling.javaapi.http.HttpDsl.header
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpRequestActionBuilder
import java.time.Duration

/** 세션 키. 요청마다 값을 덮어쓰므로 호출 전에 비워야 하는 키는 [Api.call]의 resetKeys로 넘긴다. */
object Keys {
    const val TOKEN = "token"
    const val SCHEDULE_ID = "scheduleId"
    const val VIRTUAL_USER = "virtualUser"
    const val LAST_STATUS = "lastStatus"
    const val RETRY_AFTER = "retryAfter"
    const val RATE_LIMIT_RETRY = "rateLimitRetry"
    const val RATE_LIMIT_ATTEMPT = "rateLimitAttempt"
    const val TICKET = "ticket"
    const val ADMISSION_TOKEN = "admissionToken"
    const val QUEUE_EVENT = "queueEvent"
    const val AVAILABLE_SEATS = "availableSeats"
    const val SEAT_IDS = "seatIds"
    const val BOOKING_ID = "bookingId"
    const val BOOKING_KEY = "bookingKey"
    const val PAY_KEY = "payKey"
    const val RESPONSE_BODY = "responseBody"
    const val DETAIL_STATUS = "detailStatus"
    const val FLOW_OK = "flowOk"
    const val ENTERED_AT = "enteredAt"
}

private const val MAX_RATE_LIMIT_RETRIES = 5
private const val MAX_RETRY_AFTER_SECONDS = 5L
private const val STATUS_SUCCESS_MIN = 200
private const val STATUS_SUCCESS_MAX = 299
private const val STATUS_CONFLICT = 409
private const val STATUS_TOO_MANY_REQUESTS = 429
private const val RADIX_36 = 36
private const val KEY_RANDOM_LENGTH = 8

// 409·429는 예상 응답이다. 그 밖의 4xx·5xx·네트워크 오류는 실패(KO)로 센다
private val EXPECTED_STATUSES: List<Int> = (STATUS_SUCCESS_MIN..STATUS_SUCCESS_MAX).toList() + STATUS_CONFLICT + STATUS_TOO_MANY_REQUESTS

/** Gateway 호출 래퍼. 요청 이름은 k6의 name 태그와 같아 URL에 ID가 들어가도 Gatling 리포트의 요청 종류가 늘지 않는다. */
object Api {
    val protocol =
        http
            .baseUrl(LoadEnv.baseUrl)
            // 기본 워밍업이 외부(gatling.io)로 요청을 보내므로 끈다
            .disableWarmUp()
            .acceptHeader("application/json")

    fun statusOf(session: Session): Int = if (session.contains(Keys.LAST_STATUS)) session.getInt(Keys.LAST_STATUS) else 0

    fun isOk(session: Session): Boolean = statusOf(session) in STATUS_SUCCESS_MIN..STATUS_SUCCESS_MAX

    fun isOk(status: Int): Boolean = status in STATUS_SUCCESS_MIN..STATUS_SUCCESS_MAX

    private fun authorized(request: HttpRequestActionBuilder): HttpRequestActionBuilder = request
        .header("Authorization", "Bearer #{${Keys.TOKEN}}")
        .check(
            status().saveAs(Keys.LAST_STATUS),
            status().`in`(EXPECTED_STATUSES),
            header("Retry-After").optional().saveAs(Keys.RETRY_AFTER),
        )

    /** 429면 Retry-After(최대 5초, 없으면 1초)만큼 쉬고 최대 5번 다시 보낸다. */
    fun call(request: HttpRequestActionBuilder, vararg resetKeys: String): ChainBuilder =
        exec { session -> session.set(Keys.RATE_LIMIT_RETRY, true).set(Keys.RATE_LIMIT_ATTEMPT, 0) }
            .asLongAs { session -> session.getBoolean(Keys.RATE_LIMIT_RETRY) }
            .on(
                exec { session ->
                    // 네트워크 오류로 값이 저장되지 않으면 이전 요청의 값이 남으므로 먼저 지운다
                    (listOf(Keys.LAST_STATUS, Keys.RETRY_AFTER) + resetKeys).fold(session) { current, key -> current.remove(key) }
                }.exec(authorized(request))
                    .exec { session ->
                        val attempt = session.getInt(Keys.RATE_LIMIT_ATTEMPT)
                        session
                            .set(Keys.RATE_LIMIT_RETRY, statusOf(session) == STATUS_TOO_MANY_REQUESTS && attempt < MAX_RATE_LIMIT_RETRIES)
                            .set(Keys.RATE_LIMIT_ATTEMPT, attempt + 1)
                    }.doIf { session -> session.getBoolean(Keys.RATE_LIMIT_RETRY) }
                    .then(pause { session -> retryDelay(session) }),
            )

    private fun retryDelay(session: Session): Duration {
        val seconds = session.takeIf { it.contains(Keys.RETRY_AFTER) }?.getString(Keys.RETRY_AFTER)?.toLongOrNull()
        return Duration.ofSeconds(if (seconds != null && seconds > 0) minOf(seconds, MAX_RETRY_AFTER_SECONDS) else 1)
    }

    /** 409 SEAT_ALREADY_HELD만 예상 충돌이다. 다른 409는 예상하지 못한 응답으로 센다. */
    fun isSeatAlreadyHeld(session: Session): Boolean =
        statusOf(session) == STATUS_CONFLICT && session.getString(Keys.RESPONSE_BODY)?.contains("SEAT_ALREADY_HELD") == true

    /** 멱등 키. 같은 키로 다시 보내는 경우(재결제)를 위해 세션에 저장해 쓴다. */
    fun newIdempotencyKey(prefix: String, virtualUser: Long): String = "$prefix-$virtualUser-${System.currentTimeMillis().toString(
        RADIX_36,
    )}-${java.util.UUID.randomUUID().toString().take(KEY_RANDOM_LENGTH)}"
}

/** 서비스 API 호출 묶음. 요청 이름은 k6의 name 태그와 같다. */
object Endpoints {
    fun enterQueue(): ChainBuilder = Api.call(http("queue_enter").post("/api/v1/queues/#{${Keys.SCHEDULE_ID}}/entries"))

    // SSE 티켓은 1회용이라 스트림을 열 때마다 새로 받는다
    fun issueSseTicket(): ChainBuilder = Api.call(
        http("sse_ticket")
            .post("/api/v1/sse-tickets")
            .check(jsonPath("$.ticket").optional().saveAs(Keys.TICKET)),
        Keys.TICKET,
    )

    /** 좌석 ID는 세션의 [Keys.SEAT_IDS](List<Int>)에서 읽는다. */
    fun createBooking(): ChainBuilder = Api.call(
        http("booking_create")
            .post("/api/v1/bookings")
            .transformResponse { response, _ ->
                LoadMetrics.recordBooking(
                    response.status().code(),
                    LoadEnv.errorCode(response.body().string()),
                    response.endTimestamp() - response.startTimestamp(),
                )
                response
            }
            .header("X-Admission-Token", "#{${Keys.ADMISSION_TOKEN}}")
            .header("Idempotency-Key", "#{${Keys.BOOKING_KEY}}")
            .header("Content-Type", "application/json")
            .body(
                StringBody { session ->
                    val seatIds = session.getList<Int>(Keys.SEAT_IDS).joinToString(",")
                    """{"scheduleId":${session.getLong(Keys.SCHEDULE_ID)},"seatIds":[$seatIds]}"""
                },
            ).check(
                jsonPath("$.bookingId").ofLong().optional().saveAs(Keys.BOOKING_ID),
                bodyString().saveAs(Keys.RESPONSE_BODY),
            ),
        Keys.BOOKING_ID,
        Keys.RESPONSE_BODY,
    )

    fun payBooking(): ChainBuilder = Api.call(
        http("booking_pay")
            .post("/api/v1/bookings/#{${Keys.BOOKING_ID}}/payments")
            .header("Idempotency-Key", "#{${Keys.PAY_KEY}}"),
    )

    fun bookingDetail(): ChainBuilder = Api.call(
        http("booking_detail")
            .get("/api/v1/bookings/#{${Keys.BOOKING_ID}}")
            .check(jsonPath("$.status").optional().saveAs(Keys.DETAIL_STATUS)),
        Keys.DETAIL_STATUS,
    )
}
