package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.E2eClient
import io.github.ticketing.e2e.support.EventStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MultiInstanceE2eTests {
    @Test
    fun `두 인스턴스의 순번 입장 좌석 이벤트와 재연결 결과가 일치한다`() = runTest {
        val gateway = System.getProperty("e2e.gatewayUrl", "http://localhost:8000")
        val realtime = System.getProperty("e2e.realtimeBUrl", "http://localhost:8082")
        val client = E2eClient(gateway)
        val direct = E2eClient(realtime)
        val organizer = client.signUp("ORGANIZER")
        val probe = client.signUp("USER")
        val members = List(3) { client.signUp("USER") }
        val schedule = client.createSchedule(organizer, 4)
        client.awaitSeatSnapshot(probe, schedule.id)
        val ticket = client.sseTicket(members[0])
        val first = EventStream(this, client.sse("$gateway/api/v1/streams/schedules/${schedule.id}/seats?ticket=$ticket"))
        val second = EventStream(this, client.sse("$realtime/api/v1/streams/schedules/${schedule.id}/seats", memberId = members[1].id))
        assertEquals("snapshot", first.next().event)
        assertEquals("snapshot", second.next().event)

        members.forEach { client.enterQueue(it, schedule.id) }
        members.map { member ->
            async {
                val path = "/api/v1/queues/${schedule.id}/entries/me"
                val fromGateway = async { client.request("GET", path, member) }
                val fromSecond = async { direct.request("GET", path, headers = mapOf("X-Member-Id" to member.id.toString())) }
                val left = fromGateway.await()
                val right = fromSecond.await()
                assertEquals(200, left.status)
                assertEquals(200, right.status)
                assertEquals(left.body, right.body)
            }
        }.awaitAll()
        val queue = EventStream(this, client.sse("$realtime/api/v1/streams/queues/${schedule.id}", memberId = members[0].id))
        val token = requireNotNull(queue.next("admitted").data)["admissionToken"].asString()
        val booking = client.createBooking(members[0], schedule.id, listOf(schedule.seatIds[0]), token)
        assertEquals(201, booking.status)
        val gatewayHeld = first.next("seat")
        val directHeld = second.next("seat")
        assertEquals("HELD", requireNotNull(gatewayHeld.data)["status"].asString())
        assertEquals(gatewayHeld.id, directHeld.id)
        assertEquals(gatewayHeld.data, directHeld.data)
        val lastId = assertNotNull(directHeld.id)
        second.close()

        val otherToken = client.awaitAdmission(members[1], schedule.id)
        val otherBooking = client.createBooking(members[1], schedule.id, listOf(schedule.seatIds[1]), otherToken)
        assertEquals(201, otherBooking.status)
        val changed = first.next("seat")
        val replayTicket = client.sseTicket(members[1])
        val replay = EventStream(this, client.sse("$gateway/api/v1/streams/schedules/${schedule.id}/seats?ticket=$replayTicket", lastId))
        val received = replay.next()
        assertEquals("seat", received.event)
        assertEquals(changed.id, received.id)
        assertEquals(changed.data, received.data)
        first.close()
        replay.close()
        queue.close()
    }
}
