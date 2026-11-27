package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.BatchSupport
import io.github.ticketing.e2e.support.E2eClient
import io.github.ticketing.e2e.support.Member
import kotlinx.coroutines.test.runTest
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * payment 앱을 거치는 결제 경로 E2E. fake-pg가 payment 앱(18085)을 직접 호출하고, 결과는 Kafka로 core에 돌아간다.
 * 웹훅 재전송은 payment 앱에 서명된 요청을 직접 보내 만든다(웹훅은 Gateway를 거치지 않는다).
 * 만료 후 승인 시나리오는 fake-pg의 승인 보류(`/v1/test/approval-holds`)로 "만료 시각 커밋 → 승인·웹훅 전송" 순서를 보장한다.
 */
class PaymentFlowE2eTests {
    private val gateway = System.getProperty("e2e.gatewayUrl", "http://localhost:18000")
    private val paymentUrl = System.getProperty("e2e.paymentUrl", "http://localhost:18085")
    private val fakePgUrl = System.getProperty("e2e.fakePgUrl", "http://localhost:18090")
    private val client = E2eClient(gateway)
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    @Test
    fun `같은 승인 웹훅을 다시 보내도 결제와 예매 확정은 한 번만 반영된다`() = runTest {
        val organizer = client.signUp("ORGANIZER")
        val buyer = client.signUp("USER")
        val schedule = client.createSchedule(organizer, 1)
        val booking = client.reserve(buyer, schedule, schedule.seatIds)
        client.payAndAwaitConfirmed(buyer, booking)
        val payment = paymentPsql(
            "SELECT status, pg_transaction_id, amount FROM payment.payment WHERE order_id = $booking",
        ).single().split("|")
        assertEquals("SUCCEEDED", payment[0])
        val transactionId = payment[1]
        val eventId = paymentPsql(
            "SELECT event_id FROM payment.webhook_event WHERE transaction_id = '$transactionId' AND type = 'PAYMENT_APPROVED'",
        ).first()
        val publishedBefore = approvedPublications(booking)
        assertEquals(1, publishedBefore, "승인 결과 이벤트는 처음 한 번만 발행되어야 한다")

        val replayed = approvedWebhook(eventId, transactionId, booking, payment[2])
        val statuses = listOf(postWebhook(replayed), postWebhook(replayed))
        // 같은 거래의 같은 결과는 eventId가 달라도 다시 발행하지 않는다
        val otherEventId = "e2e-replay-${System.nanoTime()}"
        val otherEvent = postWebhook(approvedWebhook(otherEventId, transactionId, booking, payment[2]))

        assertEquals(listOf(200, 200), statuses)
        assertEquals(200, otherEvent)
        assertEquals(listOf("1"), paymentPsql("SELECT count(*) FROM payment.payment WHERE order_id = $booking"))
        assertEquals(listOf("1"), paymentPsql("SELECT count(*) FROM payment.webhook_event WHERE event_id = '$eventId'"))
        assertEquals(listOf("SUCCEEDED"), paymentPsql("SELECT status FROM payment.payment WHERE order_id = $booking"))
        assertEquals(listOf("1"), paymentPsql("SELECT count(*) FROM payment.webhook_event WHERE event_id = '$otherEventId'"))
        assertEquals(publishedBefore, approvedPublications(booking))
        assertEquals("CONFIRMED", client.bookingDetail(buyer, booking)["status"].asString())
        // 정산 사본은 이벤트를 비동기로 받으므로 기다린 뒤 한 행인지 확인한다
        client.poll {
            BatchSupport.settlementPsql("SELECT count(*) FROM settlement.booking_sale WHERE booking_id = $booking") == listOf("1")
        }
        assertEquals("SUCCEEDED", paymentStatus(buyer, booking))
    }

    @Test
    fun `만료된 예매에 승인이 도착하면 환불을 완료하고 좌석을 돌려준다`() = runTest {
        val organizer = client.signUp("ORGANIZER")
        val buyer = client.signUp("USER")
        val schedule = client.createSchedule(organizer, 1)
        val booking = client.reserve(buyer, schedule, schedule.seatIds)

        // fake-pg가 이 주문의 승인을 보류하게 한 뒤 결제를 요청한다. 거래가 보류된 것을 확인하고 만료 시각을 커밋한 다음에야 승인을 푼다
        assertEquals(201, fakePg("POST", "/v1/test/approval-holds/$booking"))
        client.requestPayment(buyer, booking)
        client.poll { fakePgHoldState(booking) == "HELD" }
        // 만료 시각만 과거로 돌린다. 앱 코드에는 E2E 전용 경로가 없다
        assertEquals(
            listOf(booking.toString()),
            BatchSupport.psql(
                "UPDATE booking.booking SET expires_at = now() - interval '1 minute' WHERE id = $booking AND status = 'PENDING' RETURNING id",
            ),
        )
        assertEquals(202, fakePg("POST", "/v1/test/approval-holds/$booking/release"))

        client.poll { paymentPsql("SELECT status FROM payment.payment WHERE order_id = $booking") == listOf("REFUNDED") }
        client.poll { client.bookingDetail(buyer, booking)["status"].asString() == "EXPIRED" }
        assertEquals("REFUNDED", paymentStatus(buyer, booking))
        // 선점이 풀렸으므로 다른 구매자가 같은 좌석을 예매할 수 있다
        val other = client.signUp("USER")
        assertNotEquals(booking, client.reserve(other, schedule, schedule.seatIds))
    }

    @Test
    fun `웹훅 경로는 Gateway에서 막히고 payment 앱은 서명 없는 웹훅을 거부한다`() = runTest {
        val viaGateway = client.request("POST", "/api/v1/payments/webhooks/fake-pg", body = mapOf("eventId" to "x"))
        val unsigned = http.send(
            HttpRequest.newBuilder(URI.create("$paymentUrl/api/v1/payments/webhooks/fake-pg"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(404, viaGateway.status)
        assertEquals(401, unsigned.statusCode())
    }

    private fun fakePg(method: String, path: String): Int = http.send(
        HttpRequest.newBuilder(URI.create("$fakePgUrl$path")).method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    private fun fakePgHoldState(orderId: Long): String? {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("$fakePgUrl/v1/test/approval-holds/$orderId")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return if (response.statusCode() == 200) json.readTree(response.body())["state"].asString() else null
    }

    private suspend fun paymentStatus(member: Member, bookingId: Long): String {
        val result = client.request("GET", "/api/v1/payments/me?bookingIds=$bookingId", member)
        assertEquals(200, result.status)
        return result.body.single()["status"].asString()
    }

    private fun paymentPsql(sql: String) = BatchSupport.paymentPsql(sql)

    /** payment 앱이 이 주문의 승인 결과(PaymentSucceeded)를 발행한 횟수(리스너별 행이 아니라 이벤트 행 수의 변화로 비교한다). */
    private fun approvedPublications(orderId: Long): Int = paymentPsql(
        "SELECT count(*) FROM public.event_publication WHERE event_type LIKE '%PaymentSucceeded' AND serialized_event::jsonb ->> 'orderId' = '$orderId'",
    ).single().toInt()

    private fun approvedWebhook(eventId: String, transactionId: String, orderId: Long, amount: String): String = json.writeValueAsString(
        mapOf(
            "eventId" to eventId,
            "type" to "PAYMENT_APPROVED",
            "transactionId" to transactionId,
            "orderId" to orderId.toString(),
            "amount" to amount.toLong(),
            "occurredAt" to Instant.now().toString(),
            "failureReason" to null,
        ),
    )

    /** `PaymentWebhookSignatureVerifier`와 같은 방식(HMAC-SHA256 over "timestamp.body")으로 서명해 payment 앱에 직접 보낸다. */
    private fun postWebhook(body: String): Int {
        val secret = checkNotNull(System.getenv("E2E_WEBHOOK_SECRET")) { "E2E_WEBHOOK_SECRET이 필요합니다(scripts/e2e/run.sh로 실행)." }
        val timestamp = Instant.now().epochSecond.toString()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256")) }
        val signature = HexFormat.of().formatHex(mac.doFinal("$timestamp.$body".toByteArray(UTF_8)))
        val request = HttpRequest.newBuilder(URI.create("$paymentUrl/api/v1/payments/webhooks/fake-pg"))
            .header("Content-Type", "application/json")
            .header("X-FakePg-Timestamp", timestamp)
            .header("X-FakePg-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }
}
