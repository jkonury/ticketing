package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.E2eClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdmissionGuardE2eTests {
    @Test
    fun `입장 토큰을 검증하고 유효한 토큰은 예매를 허용한다`() = runTest {
        val gateway = System.getProperty("e2e.gatewayUrl", "http://localhost:8000")
        val client = E2eClient(gateway)
        val organizer = client.signUp("ORGANIZER")
        val member = client.signUp("USER")
        val other = client.signUp("USER")
        val first = client.createSchedule(organizer, 2)
        val second = client.createSchedule(organizer, 1)
        client.awaitSeatSnapshot(member, first.id)
        client.awaitSeatSnapshot(other, second.id)
        client.enterQueue(other, first.id)
        val memberToken = client.awaitAdmission(member, first.id)
        val otherToken = client.awaitAdmission(other, first.id)
        val wrongScheduleToken = client.awaitAdmission(other, second.id)

        val rejected = listOf(
            client.createBooking(member, first.id, first.seatIds, null) to "ADMISSION_TOKEN_REQUIRED",
            client.createBooking(member, first.id, first.seatIds, "invalid") to "ADMISSION_TOKEN_INVALID",
            client.createBooking(member, first.id, first.seatIds, otherToken) to "ADMISSION_TOKEN_INVALID",
            client.createBooking(other, first.id, first.seatIds, wrongScheduleToken) to "ADMISSION_TOKEN_INVALID",
        )

        rejected.forEach { (result, code) ->
            assertEquals(403, result.status)
            assertEquals(code, result.body["code"].asString())
        }
        for (user in listOf(member, other)) {
            val bookings = client.request("GET", "/api/v1/bookings/me", user)
            assertEquals(200, bookings.status)
            assertTrue(bookings.body["items"].isEmpty)
        }
        val ticket = client.sseTicket(member)
        val snapshot = withContext(Dispatchers.IO) {
            withTimeout(10000) {
                client.sse("$gateway/api/v1/streams/schedules/${first.id}/seats?ticket=$ticket").first {
                    it.event ==
                        "snapshot"
                }
            }
        }
        val seats = requireNotNull(snapshot.data)["seats"]
        assertEquals(first.seatIds.toSet(), seats.values().map { it["seatId"].asLong() }.toSet())
        assertTrue(seats.values().all { it["status"].asString() == "AVAILABLE" })
        val accepted = client.createBooking(member, first.id, first.seatIds, memberToken)
        assertEquals(201, accepted.status)
    }
}
