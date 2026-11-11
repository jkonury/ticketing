package io.github.ticketing.e2e.support

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class Member(val id: Long, val accessToken: String)
data class Schedule(val id: Long, val seatIds: List<Long>)
data class HttpResult(val status: Int, val body: JsonNode)
data class SseEvent(val id: String?, val event: String?, val data: JsonNode?)

class E2eClient(private val gatewayUrl: String) {
    private val client = WebClient.create(gatewayUrl)
    private val mapper = JsonMapper.builder().build()

    suspend fun request(
        method: String,
        path: String,
        member: Member? = null,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResult {
        val request = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
            .headers { target ->
                member?.let { target.setBearerAuth(it.accessToken) }
                headers.forEach { (name, value) -> target.set(name, value) }
            }
        val spec = if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(mapper.writeValueAsString(body))
        return spec.exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("{}").map {
                HttpResult(response.statusCode().value(), mapper.readTree(it))
            }
        }.awaitSingle()
    }

    suspend fun signUp(role: String): Member {
        val email = "e2e-${UUID.randomUUID()}@example.com"
        val password = "test-password-123"
        val created = request(
            "POST",
            "/api/v1/members",
            body = mapOf(
                "email" to email,
                "password" to password,
                "name" to "E2E",
                "role" to role,
            ),
        )
        check(created.status == 201) { "회원 가입 실패: ${created.status}" }
        val login = request("POST", "/api/v1/auth/tokens", body = mapOf("email" to email, "password" to password))
        check(login.status == 200) { "로그인 실패: ${login.status}" }
        return Member(created.body["id"].asLong(), login.body["accessToken"].asString())
    }

    suspend fun createSchedule(organizer: Member, seatCount: Int): Schedule {
        val unique = UUID.randomUUID().toString()
        val seats = (1..seatCount).map { mapOf("section" to "A", "row" to "1", "number" to it, "grade" to "A") }
        val venue = request("POST", "/api/v1/venues", organizer, mapOf("name" to "E2E $unique", "seats" to seats))
        check(venue.status == 201) { "공연장 생성 실패: ${venue.status}" }
        val performance = request(
            "POST",
            "/api/v1/performances",
            organizer,
            mapOf(
                "title" to "E2E $unique",
                "description" to "E2E",
                "venueId" to venue.body["id"].asLong(),
                "prices" to listOf(mapOf("grade" to "A", "price" to 10000)),
            ),
        )
        check(performance.status == 201) { "공연 생성 실패: ${performance.status}" }
        val schedule = request(
            "POST",
            "/api/v1/performances/${performance.body["id"].asLong()}/schedules",
            organizer,
            mapOf("startsAt" to Instant.now().plusSeconds(86400).toString()),
        )
        check(schedule.status == 201) { "회차 생성 실패: ${schedule.status}" }
        val id = schedule.body["id"].asLong()
        val result = request("GET", "/api/v1/schedules/$id/seats")
        check(result.status == 200) { "좌석 조회 실패: ${result.status}" }
        return Schedule(id, result.body.values().map { it["seatId"].asLong() })
    }

    suspend fun awaitSeatSnapshot(member: Member, scheduleId: Long) = poll {
        val result = request("POST", "/api/v1/queues/$scheduleId/entries", member)
        check(result.status == 201 || result.status == 200 || result.status == 404) { "대기열 등록 실패: ${result.status}" }
        result.status != 404
    }

    suspend fun enterQueue(member: Member, scheduleId: Long): JsonNode {
        val result = request("POST", "/api/v1/queues/$scheduleId/entries", member)
        check(result.status == 200 || result.status == 201) { "대기열 등록 실패: ${result.status}" }
        return result.body
    }

    suspend fun awaitAdmission(member: Member, scheduleId: Long): String {
        var token: String? = null
        poll {
            val result = request("GET", "/api/v1/queues/$scheduleId/entries/me", member)
            check(result.status == 200) { "대기열 조회 실패: ${result.status}" }
            if (result.body["status"].asString() == "ADMITTED") token = result.body["admissionToken"].asString()
            token != null
        }
        return requireNotNull(token)
    }

    suspend fun createBooking(member: Member, scheduleId: Long, seatIds: List<Long>, admissionToken: String?): HttpResult {
        val headers = mutableMapOf("Idempotency-Key" to UUID.randomUUID().toString())
        admissionToken?.let { headers["X-Admission-Token"] = it }
        return request("POST", "/api/v1/bookings", member, mapOf("scheduleId" to scheduleId, "seatIds" to seatIds), headers)
    }

    suspend fun sseTicket(member: Member): String {
        val result = request("POST", "/api/v1/sse-tickets", member)
        check(result.status == 201) { "SSE 티켓 발급 실패: ${result.status}" }
        return result.body["ticket"].asString()
    }

    fun sse(url: String, lastEventId: String? = null, memberId: Long? = null): Flow<SseEvent> = WebClient.create().get().uri(url)
        .headers {
            if (lastEventId != null) it.set("Last-Event-ID", lastEventId)
            if (memberId != null) it.set("X-Member-Id", memberId.toString())
        }
        .accept(MediaType.TEXT_EVENT_STREAM).retrieve()
        .bodyToFlux(object : ParameterizedTypeReference<ServerSentEvent<String>>() {}).asFlow()
        .map { SseEvent(it.id(), it.event(), it.data()?.let(mapper::readTree)) }

    suspend fun poll(done: suspend () -> Boolean) = withContext(Dispatchers.IO) {
        withTimeout(30000) {
            while (!done()) delay(200)
        }
    }
}
