package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.BatchSupport
import io.github.ticketing.e2e.support.CouponBookingFixture
import io.github.ticketing.e2e.support.E2eClient
import io.github.ticketing.e2e.support.FakePgClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CouponFlowE2eTests {
    private val client = E2eClient(System.getProperty("e2e.gatewayUrl", "http://localhost:18000"))

    @Test
    fun `쿠폰을 적용해 결제하면 확정되고 쿠폰이 사용 처리된다`() = runTest {
        val reserved = CouponBookingFixture.reserve(client)
        client.payAndAwaitConfirmed(reserved.buyer, reserved.bookingId)
        client.poll { client.myCoupon(reserved.buyer, reserved.definitionId)["status"].asString() == "USED" }
    }

    @Test
    fun `결제 전에 예매를 취소하면 쿠폰이 되돌아온다`() = runTest {
        val reserved = CouponBookingFixture.reserve(client)
        client.cancelBooking(reserved.buyer, reserved.bookingId)
        client.poll { client.myCoupon(reserved.buyer, reserved.definitionId)["status"].asString() == "ISSUED" }
    }

    @Test
    fun `확정 후 취소해 환불해도 쿠폰은 사용 상태로 남는다`() = runTest {
        val reserved = CouponBookingFixture.reserve(client)
        client.payAndAwaitConfirmed(reserved.buyer, reserved.bookingId)
        client.poll { client.myCoupon(reserved.buyer, reserved.definitionId)["status"].asString() == "USED" }
        client.cancelBooking(reserved.buyer, reserved.bookingId)
        client.poll { client.bookingDetail(reserved.buyer, reserved.bookingId)["status"].asString() == "CANCELLED" }
        delay(SETTLE_MILLIS)
        assertEquals("USED", client.myCoupon(reserved.buyer, reserved.definitionId)["status"].asString())
    }

    @Test
    fun `쿠폰 예매의 결제가 실패하면 주문이 취소되고 쿠폰과 좌석을 돌려준다`() = runTest {
        val reserved = CouponBookingFixture.reserve(client)
        val fakePg = FakePgClient()
        fakePg.registerHold(reserved.bookingId)
        client.requestPayment(reserved.buyer, reserved.bookingId)
        client.poll { fakePg.holdState(reserved.bookingId) == "HELD" }

        fakePg.failHold(reserved.bookingId)

        client.poll {
            client.paymentFor(reserved.buyer, reserved.bookingId)?.get("status")?.asString() == "FAILED"
        }
        client.poll { client.bookingDetail(reserved.buyer, reserved.bookingId)["status"].asString() == "CANCELLED" }
        assertEquals(
            listOf("PAYMENT_FAILED"),
            BatchSupport.psql("SELECT cancel_reason FROM booking.booking WHERE id = ${reserved.bookingId}"),
        )
        client.poll { client.myCoupon(reserved.buyer, reserved.definitionId)["status"].asString() == "ISSUED" }
        assertEquals(reserved.issuedCouponId, client.myCoupon(reserved.buyer, reserved.definitionId)["id"].asLong())
        assertEquals(emptyList(), BatchSupport.psql("SELECT seat_id FROM booking.sold_seat WHERE booking_id = ${reserved.bookingId}"))
        val other = client.signUp("USER")
        assertNotEquals(reserved.bookingId, client.reserve(other, reserved.schedule, reserved.seatIds))
    }

    private companion object {
        const val SETTLE_MILLIS = 2000L
    }
}
