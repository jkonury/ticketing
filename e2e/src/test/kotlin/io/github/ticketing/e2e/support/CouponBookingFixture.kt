package io.github.ticketing.e2e.support

import kotlin.test.assertEquals

data class CouponBooking(
    val buyer: Member,
    val schedule: Schedule,
    val seatIds: List<Long>,
    val bookingId: Long,
    val definitionId: Long,
    val issuedCouponId: Long,
)

object CouponBookingFixture {
    suspend fun reserve(client: E2eClient, discount: Long = 3000L): CouponBooking {
        val admin = client.adminLogin()
        val organizer = client.signUp("ORGANIZER")
        val buyer = client.signUp("USER")
        val schedule = client.createSchedule(organizer, 2)
        val seatIds = schedule.seatIds.take(1)
        val definitionId = client.createFixedCoupon(admin, discount)
        client.grantCoupon(admin, definitionId, buyer.id)
        val issued = client.myCoupon(buyer, definitionId)
        assertEquals("ISSUED", issued["status"].asString())
        val issuedCouponId = issued["id"].asLong()
        val bookingId = client.reserve(buyer, schedule, seatIds)

        client.applyCoupon(buyer, bookingId, issuedCouponId)
        client.poll { client.bookingDetail(buyer, bookingId)["coupon"]["state"].asString() != "RESERVING" }
        val detail = client.bookingDetail(buyer, bookingId)
        assertEquals("RESERVED", detail["coupon"]["state"].asString())
        assertEquals(discount, detail["discountAmount"].asLong())
        assertEquals(detail["listAmount"].asLong() - discount, detail["payableAmount"].asLong())
        assertEquals("RESERVED", client.myCoupon(buyer, definitionId)["status"].asString())
        return CouponBooking(buyer, schedule, seatIds, bookingId, definitionId, issuedCouponId)
    }
}
