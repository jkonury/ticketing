package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.E2eClient
import io.github.ticketing.e2e.support.Member
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CouponFlowE2eTests {
    private val client = E2eClient(System.getProperty("e2e.gatewayUrl", "http://localhost:18000"))

    private class Reserved(val buyer: Member, val bookingId: Long, val definitionId: Long)

    /** 쿠폰을 지급받은 구매자가 예매 후 쿠폰을 적용해 RESERVED가 되고 결제 금액이 할인된 상태까지 만든다. */
    private suspend fun reserveWithCoupon(): Reserved {
        val admin = client.adminLogin()
        val organizer = client.signUp("ORGANIZER")
        val buyer = client.signUp("USER")
        val schedule = client.createSchedule(organizer, 2)
        val definitionId = client.createFixedCoupon(admin, DISCOUNT)
        client.grantCoupon(admin, definitionId, buyer.id)
        val issued = client.myCoupon(buyer, definitionId)
        assertEquals("ISSUED", issued["status"].asString())

        client.awaitSeatSnapshot(buyer, schedule.id)
        client.enterQueue(buyer, schedule.id)
        val token = client.awaitAdmission(buyer, schedule.id)
        val booking = client.createBooking(buyer, schedule.id, schedule.seatIds.take(1), token)
        assertEquals(201, booking.status)
        val bookingId = booking.body["bookingId"].asLong()

        client.applyCoupon(buyer, bookingId, issued["id"].asLong())
        client.poll { client.bookingDetail(buyer, bookingId)["coupon"]["state"].asString() != "RESERVING" }
        val detail = client.bookingDetail(buyer, bookingId)
        assertEquals("RESERVED", detail["coupon"]["state"].asString())
        assertEquals(DISCOUNT, detail["discountAmount"].asLong())
        assertEquals(detail["listAmount"].asLong() - DISCOUNT, detail["payableAmount"].asLong())
        assertEquals("RESERVED", client.myCoupon(buyer, definitionId)["status"].asString())
        return Reserved(buyer, bookingId, definitionId)
    }

    private suspend fun payAndConfirm(reserved: Reserved) {
        val payment = client.request(
            "POST",
            "/api/v1/bookings/${reserved.bookingId}/payments",
            reserved.buyer,
            headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertEquals(202, payment.status)
        client.poll { client.bookingDetail(reserved.buyer, reserved.bookingId)["status"].asString() == "CONFIRMED" }
    }

    @Test
    fun `쿠폰을 적용해 결제하면 확정되고 쿠폰이 사용 처리된다`() = runTest {
        val reserved = reserveWithCoupon()
        payAndConfirm(reserved)
        client.poll { client.myCoupon(reserved.buyer, reserved.definitionId)["status"].asString() == "USED" }
    }

    @Test
    fun `결제 전에 예매를 취소하면 쿠폰이 되돌아온다`() = runTest {
        val reserved = reserveWithCoupon()
        client.cancelBooking(reserved.buyer, reserved.bookingId)
        client.poll { client.myCoupon(reserved.buyer, reserved.definitionId)["status"].asString() == "ISSUED" }
    }

    @Test
    fun `확정 후 취소해 환불해도 쿠폰은 사용 상태로 남는다`() = runTest {
        val reserved = reserveWithCoupon()
        payAndConfirm(reserved)
        client.poll { client.myCoupon(reserved.buyer, reserved.definitionId)["status"].asString() == "USED" }
        client.cancelBooking(reserved.buyer, reserved.bookingId)
        client.poll { client.bookingDetail(reserved.buyer, reserved.bookingId)["status"].asString() == "CANCELLED" }
        delay(SETTLE_MILLIS)
        assertEquals("USED", client.myCoupon(reserved.buyer, reserved.definitionId)["status"].asString())
    }

    private companion object {
        const val DISCOUNT = 3000L
        const val SETTLE_MILLIS = 2000L
    }
}
