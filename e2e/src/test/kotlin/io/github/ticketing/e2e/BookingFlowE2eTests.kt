package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.E2eClient
import io.github.ticketing.e2e.support.EventStream
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookingFlowE2eTests {
    @Test
    fun `대기열 입장부터 결제 확정까지 다른 관람객에게 좌석 변경이 전달된다`() = runTest {
        val gateway = System.getProperty("e2e.gatewayUrl", "http://localhost:18000")
        val client = E2eClient(gateway)
        val organizer = client.signUp("ORGANIZER")
        val buyer = client.signUp("USER")
        val viewer = client.signUp("USER")
        val schedule = client.createSchedule(organizer, 4)
        client.awaitSeatSnapshot(viewer, schedule.id)
        val seatTicket = client.sseTicket(viewer)
        val queueTicket = client.sseTicket(buyer)
        val seats = EventStream(this, client.sse("$gateway/api/v1/streams/schedules/${schedule.id}/seats?ticket=$seatTicket"))
        assertEquals("snapshot", seats.next().event)

        client.enterQueue(buyer, schedule.id)
        val queue = EventStream(this, client.sse("$gateway/api/v1/streams/queues/${schedule.id}?ticket=$queueTicket"))
        val firstQueueEvent = queue.next()
        val admission = if (firstQueueEvent.event == "position") queue.next("admitted") else firstQueueEvent
        assertEquals("admitted", admission.event)
        val token = requireNotNull(admission.data)["admissionToken"].asString()
        val selected = schedule.seatIds.take(2)
        val booking = client.createBooking(buyer, schedule.id, selected, token)
        assertEquals(201, booking.status)
        val held = seats.next("seat")
        assertEquals("HELD", requireNotNull(held.data)["status"].asString())
        assertEquals(selected.toSet(), held.data["seatIds"].values().map { it.asLong() }.toSet())

        val id = booking.body["bookingId"].asLong()
        val payment = client.request(
            "POST",
            "/api/v1/bookings/$id/payments",
            buyer,
            headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertEquals(202, payment.status)
        client.poll {
            val result = client.request("GET", "/api/v1/bookings/$id", buyer)
            assertEquals(200, result.status)
            result.body["status"].asString() == "CONFIRMED"
        }
        val sold = seats.next("seat")
        assertEquals("SOLD", requireNotNull(sold.data)["status"].asString())
        assertEquals(selected.toSet(), sold.data["seatIds"].values().map { it.asLong() }.toSet())
        assertTrue(!sold.id.isNullOrBlank())
        seats.close()
        queue.close()
    }
}
