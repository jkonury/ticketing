package io.github.ticketing.payment.flow

import io.github.ticketing.EventCollector
import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.KafkaTestSupport
import io.github.ticketing.KafkaTopics
import io.github.ticketing.MutableClock
import io.github.ticketing.TestPg
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.booking.BookingExpired
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.holdKey
import io.github.ticketing.booking.adapter.required.redis.RedisSeatHoldAdapter.Companion.snapshotKey
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.domain.ScheduleId
import io.github.ticketing.booking.domain.SeatId
import io.github.ticketing.expectStatus
import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.PaymentSucceeded
import io.github.ticketing.payment.application.port.provided.RequestPaymentCommand
import io.github.ticketing.payment.application.port.provided.RequestPaymentUseCase
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgPort
import io.github.ticketing.payment.application.port.required.PgRequestResult
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PaymentFlowTests(
    private val pg: TestPg,
    private val requestPayment: RequestPaymentUseCase,
    private val mockMvcTester: MockMvcTester,
    private val register: RegisterScheduleSeatsUseCase,
    private val redis: StringRedisTemplate,
    private val jdbc: JdbcTemplate,
    private val clock: MutableClock,
    private val collector: EventCollector,
    private val topics: KafkaTopics,
    private val kafkaProperties: KafkaProperties,
) : IntegrationTestSupport() {

    private val json = JsonMapper.builder().build()

    private fun get(path: String) = mockMvcTester.get().uri(path)
    private fun post(path: String) = mockMvcTester.post().uri(path)

    private fun newSchedule(seatCount: Int = 4): Long {
        val scheduleId = System.nanoTime()
        register.register(
            RegisterScheduleSeatsCommand(
                scheduleId,
                1,
                "결제 흐름 공연",
                2,
                Instant.parse("2027-01-01T10:00:00Z"),
                (1..seatCount).map { RegisterScheduleSeatsCommand.SeatInfo(it.toLong(), "R", "A구역 ${it}번", BigDecimal(100000)) },
            ),
        )
        return scheduleId
    }

    private fun token(): String {
        val email = "u-${UUID.randomUUID()}@example.com"
        val body = { m: Map<String, String> -> json.writeValueAsString(m) }
        post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("email" to email, "password" to "password123", "name" to "홍길동", "role" to "USER")))
            .exchange()
            .expectStatus(HttpStatus.CREATED)
        val res = post("/api/v1/auth/tokens").contentType(MediaType.APPLICATION_JSON)
            .content(body(mapOf("email" to email, "password" to "password123")))
            .exchange()
            .expectStatus(HttpStatus.OK)
            .response.contentAsString
        return json.readValue(res, Map::class.java)["accessToken"] as String
    }

    private fun book(token: String, scheduleId: Long, seatIds: List<Long>): Long {
        val res = post("/api/v1/bookings").header("Authorization", "Bearer $token").header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to seatIds)))
            .exchange()
            .expectStatus(HttpStatus.CREATED)
            .response.contentAsString
        return (json.readValue(res, Map::class.java)["bookingId"] as Number).toLong()
    }

    private fun pay(token: String, bookingId: Long, key: String = UUID.randomUUID().toString()): MvcTestResult =
        post("/api/v1/bookings/$bookingId/payments").header("Authorization", "Bearer $token").header("Idempotency-Key", key).exchange()

    private fun cancel(token: String, bookingId: Long): MvcTestResult =
        post("/api/v1/bookings/$bookingId/cancel").header("Authorization", "Bearer $token").exchange()

    private fun statusOf(token: String, bookingId: Long): String {
        val res = get("/api/v1/bookings/$bookingId").header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus(HttpStatus.OK)
            .response.contentAsString
        return json.readValue(res, Map::class.java)["status"] as String
    }

    private fun awaitStatus(token: String, bookingId: Long, expected: String) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(statusOf(token, bookingId)).isEqualTo(expected) }
    }

    private fun paymentStatus(id: Long): String? =
        jdbc.queryForList("select status from payment.payment where order_id = ?", String::class.java, id).firstOrNull()

    private fun awaitPayment(id: Long, expected: String) = await().atMost(Duration.ofSeconds(10)).untilAsserted {
        assertThat(paymentStatus(id)).isEqualTo(expected)
    }

    private fun pgTransactionOf(id: Long): String? =
        jdbc.queryForList("select pg_transaction_id from payment.payment where order_id = ?", String::class.java, id).firstOrNull()

    /** 결제 요청 이벤트를 Kafka에서 받아 PG가 접수해 거래가 연결될 때까지 기다린다. */
    private fun awaitRequestHandled(id: Long) = await().atMost(Duration.ofSeconds(15)).untilAsserted {
        assertThat(pgTransactionOf(id)).isNotNull()
    }

    /** 환불 요청 이벤트를 Kafka에서 받아 PG 환불이 요청될 때까지 기다린다. */
    private fun awaitRefundRequested(id: Long) = await().atMost(Duration.ofSeconds(15)).untilAsserted {
        assertThat(pg.refunds).contains("tx-$id" to "refund-$id")
    }

    private fun pgRequestsOf(id: Long) = pg.requests.filter { it.orderId == id.toString() }

    private fun refundsOf(id: Long) = pg.refunds.filter { it.first == "tx-$id" }

    private fun webhook(id: Long, type: String, eventId: String = UUID.randomUUID().toString()): MvcTestResult {
        val timestamp = clock.instant().epochSecond.toString()
        val failureReason = if (type == "PAYMENT_FAILED") "\"declined\"" else "null"
        val raw = """{"eventId":"$eventId","type":"$type","transactionId":"tx-$id","orderId":"$id","amount":100000,""" +
            """"occurredAt":"${clock.instant()}","failureReason":$failureReason}"""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("test-webhook-secret".toByteArray(), "HmacSHA256"))
        val signature = HexFormat.of().formatHex(mac.doFinal("$timestamp.$raw".toByteArray()))
        return post("/api/v1/payments/webhooks/fake-pg").contentType(MediaType.APPLICATION_JSON)
            .header("X-FakePg-Timestamp", timestamp).header("X-FakePg-Signature", signature).content(raw)
            .exchange()
    }

    private fun start(): Pair<String, Long> {
        val token = token()
        val id = book(token, newSchedule(), listOf(1))
        pay(token, id).expectStatus(HttpStatus.ACCEPTED)
        awaitPayment(id, "REQUESTED")
        return token to id
    }

    private fun count(type: Class<*>, id: Long): Int = collector.events.count {
        type.isInstance(it) && when (it) {
            is PaymentSucceeded -> it.orderId == id
            is PaymentFailed -> it.orderId == id
            is PaymentRefunded -> it.orderId == id
            else -> false
        }
    }

    @Test
    fun `승인 웹훅은 결제와 예매를 확정하고 중복을 무시한다`() {
        val (token, id) = start()
        awaitRequestHandled(id)
        val event = UUID.randomUUID().toString()

        webhook(id, "PAYMENT_APPROVED", event).expectStatus(HttpStatus.OK)
        webhook(id, "PAYMENT_APPROVED", event).expectStatus(HttpStatus.OK)
        webhook(id, "PAYMENT_APPROVED").expectStatus(HttpStatus.OK)
        awaitStatus(token, id, "CONFIRMED")

        assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED")
        assertThat(jdbc.queryForObject("select count(*) from booking.sold_seat where booking_id = ?", Int::class.java, id)).isEqualTo(1)
        assertThat(count(PaymentSucceeded::class.java, id)).isEqualTo(1)
    }

    @Test
    fun `거절과 실패 웹훅은 예매를 취소하고 좌석을 반환한다`() {
        pg.paymentResult = "rejected"
        val token = token()
        val schedule = newSchedule()
        val id = book(token, schedule, listOf(1))
        pay(token, id).expectStatus(HttpStatus.ACCEPTED)
        awaitStatus(token, id, "CANCELLED")
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(snapshot(schedule, 1)).isEqualTo("AVAILABLE") }

        assertThat(count(PaymentFailed::class.java, id)).isEqualTo(1)
        assertThat(collector.of(BookingCancelled::class.java) { it.bookingId == id }.single().reason).isEqualTo("PAYMENT_FAILED")

        pg.paymentResult = "accepted"
        val (otherToken, other) = start()
        awaitRequestHandled(other)
        webhook(other, "PAYMENT_FAILED").expectStatus(HttpStatus.OK)
        awaitStatus(otherToken, other, "CANCELLED")

        assertThat(count(PaymentFailed::class.java, other)).isEqualTo(1)
        assertThat(collector.of(BookingCancelled::class.java) { it.bookingId == other }.single().reason).isEqualTo("PAYMENT_FAILED")
    }

    @Test
    fun `미확정 결제는 컨슈머 재시도로 같은 키를 재사용해 복구한다`() {
        pg.paymentResult = "unknown"
        val (token, id) = start()
        await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(pgRequestsOf(id)).isNotEmpty() }

        pg.paymentResult = "accepted"
        awaitRequestHandled(id)

        assertThat(pgRequestsOf(id).map { it.idempotencyKey }).hasSizeGreaterThanOrEqualTo(2).containsOnly("booking-$id")

        webhook(id, "PAYMENT_APPROVED").expectStatus(HttpStatus.OK)
        awaitStatus(token, id, "CONFIRMED")
    }

    @Test
    fun `확정 취소와 만료 후 승인은 환불을 완료한다`() {
        val (token, id) = start()
        awaitRequestHandled(id)
        webhook(id, "PAYMENT_APPROVED").expectStatus(HttpStatus.OK)
        awaitStatus(token, id, "CONFIRMED")

        cancel(token, id).expectStatus(HttpStatus.OK)

        assertThat(statusOf(token, id)).isEqualTo("CANCELLED")
        awaitPayment(id, "REFUND_REQUESTED")
        awaitRefundRequested(id)
        assertThat(pg.refunds).contains("tx-$id" to "refund-$id")

        webhook(id, "REFUND_COMPLETED").expectStatus(HttpStatus.OK)
        awaitPayment(id, "REFUNDED")
        webhook(id, "PAYMENT_APPROVED").expectStatus(HttpStatus.OK)
        webhook(id, "REFUND_COMPLETED").expectStatus(HttpStatus.OK)

        assertThat(count(PaymentSucceeded::class.java, id)).isEqualTo(1)
        assertThat(count(PaymentRefunded::class.java, id)).isEqualTo(1)

        val (lateToken, late) = start()
        awaitRequestHandled(late)
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        webhook(late, "PAYMENT_APPROVED").expectStatus(HttpStatus.OK)
        awaitStatus(lateToken, late, "EXPIRED")
        awaitRefundRequested(late)
        webhook(late, "REFUND_COMPLETED").expectStatus(HttpStatus.OK)
        awaitPayment(late, "REFUNDED")
    }

    @Test
    fun `환불 거절과 미확정은 컨슈머 재시도로 같은 키를 재사용한다`() {
        for (failure in listOf("unknown", "rejected")) {
            pg.refundResult = failure
            val (token, id) = start()
            awaitRequestHandled(id)
            webhook(id, "PAYMENT_APPROVED").expectStatus(HttpStatus.OK)
            awaitStatus(token, id, "CONFIRMED")
            cancel(token, id).expectStatus(HttpStatus.OK)
            await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(refundsOf(id)).isNotEmpty() }
            assertThat(paymentStatus(id)).isEqualTo("REFUND_REQUESTED")

            pg.refundResult = "accepted"
            await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(refundsOf(id)).hasSizeGreaterThanOrEqualTo(2) }
            KafkaTestSupport.awaitConsumed(kafkaProperties.bootstrapServers, topics.refundRequested, id.toString(), "ticketing-payment", 1)
            assertThat(
                KafkaTestSupport.recordsOf(kafkaProperties.bootstrapServers, topics.deadLetterOf(topics.refundRequested), id.toString()),
            ).isEmpty()

            assertThat(refundsOf(id).map { it.second }).containsOnly("refund-$id")

            webhook(id, "REFUND_COMPLETED").expectStatus(HttpStatus.OK)
            awaitPayment(id, "REFUNDED")
        }
    }

    @Test
    fun `PG 응답 전에 도착한 웹훅은 거래를 연결하고 지연 응답도 상태를 유지한다`() {
        val gate = CountDownLatch(1)
        pg.gate = gate
        try {
            val (token, id) = start()
            await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(pg.requests.any { it.orderId == id.toString() }).isTrue() }

            webhook(id, "PAYMENT_APPROVED").expectStatus(HttpStatus.OK)
            awaitStatus(token, id, "CONFIRMED")

            gate.countDown()
            awaitRequestHandled(id)

            assertThat(paymentStatus(id)).isEqualTo("SUCCEEDED")
            assertThat(count(PaymentSucceeded::class.java, id)).isEqualTo(1)
        } finally {
            gate.countDown()
            pg.gate = null
        }
    }

    @Test
    fun `같은 웹훅의 병렬 전달과 재전송은 하나의 확정으로 수렴한다`() {
        val (token, id) = start()
        awaitRequestHandled(id)
        val same = UUID.randomUUID().toString()
        val ids = listOf(same, same, UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val pool = Executors.newFixedThreadPool(ids.size)
        try {
            val ready = CountDownLatch(1)
            val futures = ids.map { event ->
                pool.submit<Int> {
                    ready.await()
                    webhook(id, "PAYMENT_APPROVED", event).response.status
                }
            }

            ready.countDown()
            futures.forEachIndexed { index, result ->
                val response = result.get(20, TimeUnit.SECONDS)
                assertThat(response).isIn(200, 409)
                if (response == 409) webhook(id, "PAYMENT_APPROVED", ids[index]).expectStatus(HttpStatus.OK)
            }
        } finally {
            pool.shutdownNow()
        }

        awaitStatus(token, id, "CONFIRMED")
        assertThat(count(PaymentSucceeded::class.java, id)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from booking.sold_seat where booking_id = ?", Int::class.java, id)).isEqualTo(1)
    }

    @Test
    fun `같은 결제 요청을 병렬로 처리해도 하나의 거래와 결제로 수렴한다`() {
        val gate = CountDownLatch(1)
        pg.gate = gate
        try {
            val (token, id) = start()
            await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(pgRequestsOf(id)).isNotEmpty() }
            val request = collector.of(PaymentRequested::class.java) { it.orderId == id }.single()
            val command =
                RequestPaymentCommand(request.orderId, request.memberId, request.amount, request.idempotencyKey, request.requestedAt)

            // Kafka 리스너가 PG 응답을 기다리는 동안 같은 요청이 다른 스레드에서 중복 처리된다(재전달·리밸런스 상황을 가정)
            val pool = Executors.newFixedThreadPool(2)
            val futures = try {
                (1..2).map { pool.submit { runCatching { requestPayment.request(command) } } }
            } finally {
                pool.shutdown()
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted { assertThat(pgRequestsOf(id)).hasSizeGreaterThanOrEqualTo(2) }

            gate.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
            awaitRequestHandled(id)

            assertThat(pg.transactions.keys.count { it == "booking-$id" }).isEqualTo(1)
            assertThat(jdbc.queryForObject("select count(*) from payment.payment where order_id = ?", Int::class.java, id)).isEqualTo(1)

            webhook(id, "PAYMENT_APPROVED").expectStatus(HttpStatus.OK)
            awaitStatus(token, id, "CONFIRMED")
        } finally {
            gate.countDown()
            pg.gate = null
        }
    }

    private fun snapshot(scheduleId: Long, seatId: Long): String? =
        redis.opsForHash<String, String>().get(snapshotKey(ScheduleId(scheduleId)), seatId.toString())
}
