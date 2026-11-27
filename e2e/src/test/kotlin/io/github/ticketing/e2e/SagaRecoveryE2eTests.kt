package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.BatchSupport
import io.github.ticketing.e2e.support.CouponBooking
import io.github.ticketing.e2e.support.CouponBookingFixture
import io.github.ticketing.e2e.support.E2eClient
import io.github.ticketing.e2e.support.FakePgClient
import io.github.ticketing.e2e.support.SagaBroker
import io.github.ticketing.e2e.support.SagaKafka
import io.github.ticketing.e2e.support.SagaPaymentProcess
import io.github.ticketing.e2e.support.SagaTopic
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes

class SagaRecoveryE2eTests {
    private val client = E2eClient(System.getProperty("e2e.gatewayUrl", "http://localhost:18000"))
    private val fakePg = FakePgClient()

    @Test
    fun `브로커 복구 후 결제를 재발행하면 만료된 예매를 환불하고 쿠폰과 좌석을 돌려준다`() = runTest(timeout = 18.minutes) {
        val booking = CouponBookingFixture.reserve(client)
        val order = booking.bookingId
        val requestDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
        fakePg.registerHold(order)
        client.requestPayment(booking.buyer, order)
        client.poll { fakePg.holdState(order) == "HELD" }
        expirePaymentWindow(order)

        val broker = SagaBroker()
        val payment = SagaPaymentProcess()
        SagaKafka(order, SagaTopic.PAYMENT_SUCCEEDED, SagaTopic.REFUND_REQUESTED, SagaTopic.PAYMENT_REFUNDED).use { kafka ->
            try {
                val failedPublication = approveWhileBrokerPaused(booking, broker)
                client.poll { kafka.brokerResponds() }
                payment.stop()
                payment.restore()

                client.poll(timeoutMillis = 90_000) {
                    BatchSupport.paymentPsql(
                        "SELECT id FROM public.event_publication WHERE id = '$failedPublication' AND completion_date IS NOT NULL",
                    ) == listOf(failedPublication.toString())
                }
                client.poll { client.bookingDetail(booking.buyer, order)["status"].asString() == "EXPIRED" }
                awaitRefund(booking, requestDate)
                awaitCoupon(booking, "ISSUED")
                assertSeatAvailable(booking)
                assertRecoveryComplete(order, kafka)
            } finally {
                withContext(NonCancellable) { payment.restore() }
            }
        }
    }

    private fun expirePaymentWindow(order: Long) {
        assertEquals(
            listOf(order.toString()),
            BatchSupport.psql(
                "UPDATE booking.booking SET expires_at = now() - interval '1 minute' WHERE id = $order AND status = 'PENDING' RETURNING id",
            ),
        )
    }

    private suspend fun approveWhileBrokerPaused(booking: CouponBooking, broker: SagaBroker): UUID {
        val order = booking.bookingId
        try {
            broker.pause()
            fakePg.releaseHold(order)
            var failed: List<String> = emptyList()
            client.poll {
                failed = BatchSupport.paymentPsql(
                    "SELECT id FROM public.event_publication WHERE event_type LIKE '%PaymentSucceeded' " +
                        "AND serialized_event::jsonb ->> 'orderId' = '$order' AND completion_date IS NULL AND status = 'FAILED'",
                )
                failed.isNotEmpty()
            }
            assertEquals(listOf("SUCCEEDED"), BatchSupport.paymentPsql("SELECT status FROM payment.payment WHERE order_id = $order"))
            assertEquals("PENDING", client.bookingDetail(booking.buyer, order)["status"].asString())
            assertEquals("RESERVED", client.myCoupon(booking.buyer, booking.definitionId)["status"].asString())
            return UUID.fromString(failed.single())
        } finally {
            broker.resume()
        }
    }

    private suspend fun awaitRefund(booking: CouponBooking, requestDate: LocalDate) {
        val order = booking.bookingId
        client.poll(timeoutMillis = 90_000) {
            client.paymentFor(booking.buyer, order)?.get("status")?.asString() == "REFUNDED"
        }
        client.poll { fakePg.transactions(order, requestDate).singleOrNull()?.get("status")?.asString() == "REFUNDED" }
    }

    private suspend fun awaitCoupon(booking: CouponBooking, status: String) {
        client.poll { client.myCoupon(booking.buyer, booking.definitionId)["status"].asString() == status }
        assertEquals(booking.issuedCouponId, client.myCoupon(booking.buyer, booking.definitionId)["id"].asLong())
    }

    private suspend fun assertSeatAvailable(booking: CouponBooking) {
        assertEquals(emptyList(), BatchSupport.psql("SELECT seat_id FROM booking.sold_seat WHERE booking_id = ${booking.bookingId}"))
        val other = client.signUp("USER")
        assertNotEquals(booking.bookingId, client.reserve(other, booking.schedule, booking.seatIds))
    }

    private suspend fun assertRecoveryComplete(order: Long, kafka: SagaKafka) {
        client.poll { kafka.consumed() }
        kafka.assertNoDeadLetters()
        val unfinished = "SELECT id FROM public.event_publication WHERE completion_date IS NULL " +
            "AND (serialized_event::jsonb ->> 'orderId' = '$order' OR serialized_event::jsonb ->> 'bookingId' = '$order')"
        client.poll {
            BatchSupport.psql(unfinished).isEmpty() && BatchSupport.paymentPsql(unfinished).isEmpty()
        }
    }
}
