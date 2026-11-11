package io.github.ticketing.payment.adapter.required.pg

import com.sun.net.httpserver.HttpServer
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgRequestResult
import io.github.ticketing.payment.application.port.required.PgTransactionStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FakePgAdapterTests {

    private val builder = RestClient.builder().baseUrl("http://localhost:8090")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val adapter = FakePgAdapter(builder.build())
    private val payment = PgPaymentRequest("order-1", BigDecimal("1000.00"), "pay-1")

    @Test
    fun `거래 조회는 날짜와 응답을 매핑한다`() {
        server.expect(requestTo("http://localhost:8090/v1/transactions?date=2026-11-11"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(
                    """[{"transactionId":"tx","orderId":"123","amount":100,"status":"APPROVED","requestedAt":"2026-11-11T00:00:00Z","approvedAt":"2026-11-11T00:01:00Z","refundedAt":null}]""",
                ),
            )

        val transaction = adapter.findTransactions(LocalDate.parse("2026-11-11")).single()

        assertThat(transaction.orderId).isEqualTo(123)
        assertThat(transaction.status).isEqualTo(PgTransactionStatus.APPROVED)
        assertThat(transaction.amount).isEqualByComparingTo("100")
        assertThat(transaction.approvedAt).isNotNull()
    }

    @Test
    fun `거래 조회 오류와 잘못된 주문 번호는 예외로 전파한다`() {
        server.expect(requestTo("http://localhost:8090/v1/transactions?date=2026-11-11"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        server.expect(requestTo("http://localhost:8090/v1/transactions?date=2026-11-11"))
            .andRespond(
                withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(
                    """[{"transactionId":"tx","orderId":"invalid","amount":100,"status":"PENDING","requestedAt":"2026-11-11T00:00:00Z"}]""",
                ),
            )

        assertThatThrownBy { adapter.findTransactions(LocalDate.parse("2026-11-11")) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { adapter.findTransactions(LocalDate.parse("2026-11-11")) }.isInstanceOf(NumberFormatException::class.java)
    }

    @Test
    fun `결제 요청은 정수 금액과 멱등 키를 보내고 접수를 반환한다`() {
        server.expect(requestTo("http://localhost:8090/v1/payments"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key", "pay-1"))
            .andExpect(content().json("""{"orderId":"order-1","amount":1000}"""))
            .andRespond(
                withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"transactionId":"tx-1","status":"PENDING"}"""),
            )

        assertThat(adapter.requestPayment(payment)).isEqualTo(PgRequestResult.Accepted("tx-1"))

        server.verify()
    }

    @Test
    fun `환불 요청은 본문 없이 같은 거래의 접수를 반환한다`() {
        server.expect(requestTo("http://localhost:8090/v1/payments/tx-1/refund"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key", "refund-1"))
            .andExpect(content().string(""))
            .andRespond(
                withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                    .body("""{"transactionId":"tx-1","status":"REFUNDED"}"""),
            )

        assertThat(adapter.requestRefund("tx-1", "refund-1")).isEqualTo(PgRequestResult.Accepted("tx-1"))

        server.verify()
    }

    @Test
    fun `클라이언트 오류는 거절하고 서버 오류는 미확정으로 분류한다`() {
        server.expect(requestTo("http://localhost:8090/v1/payments")).andRespond(withStatus(HttpStatus.CONFLICT).body("secret"))
        server.expect(
            requestTo("http://localhost:8090/v1/payments"),
        ).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("secret"))

        assertThat(adapter.requestPayment(payment)).isEqualTo(PgRequestResult.Rejected("HTTP_4XX"))
        assertThat(adapter.requestPayment(payment)).isEqualTo(PgRequestResult.Unknown("HTTP_ERROR"))
    }

    @Test
    fun `예상하지 않은 응답과 환불 거래 불일치는 미확정이다`() {
        server.expect(requestTo("http://localhost:8090/v1/payments")).andRespond(withStatus(HttpStatus.OK))
        server.expect(
            requestTo("http://localhost:8090/v1/payments"),
        ).andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("invalid"))
        server.expect(
            requestTo("http://localhost:8090/v1/payments/tx-1/refund"),
        ).andRespond(
            withStatus(
                HttpStatus.ACCEPTED,
            ).contentType(MediaType.APPLICATION_JSON).body("""{"transactionId":"tx-2","status":"REFUNDED"}"""),
        )

        assertThat(adapter.requestPayment(payment)).isInstanceOf(PgRequestResult.Unknown::class.java)
        assertThat(adapter.requestPayment(payment)).isInstanceOf(PgRequestResult.Unknown::class.java)
        assertThat(adapter.requestRefund("tx-1", "refund-1")).isInstanceOf(PgRequestResult.Unknown::class.java)
    }

    @Test
    fun `소수나 범위를 초과하는 금액은 손실 없이 변환할 수 없다`() {
        assertThatThrownBy {
            adapter.requestPayment(payment.copy(amount = BigDecimal("1.5")))
        }.isInstanceOf(ArithmeticException::class.java)
        assertThatThrownBy {
            adapter.requestPayment(payment.copy(amount = BigDecimal("9223372036854775808")))
        }.isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `실제 서버의 응답 지연과 연결 종료는 미확정이다`() {
        val latch = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.executor = executor
        http.createContext("/v1/payments") { exchange ->
            if (exchange.requestHeaders.getFirst("Idempotency-Key") == "pay-1") {
                latch.await(2, TimeUnit.SECONDS)
            }
            exchange.close()
        }
        http.start()

        try {
            val properties =
                PaymentPgProperties(
                    baseUrl = "http://127.0.0.1:${http.address.port}",
                    readTimeout = Duration.ofMillis(100),
                    webhookSecret = "secret",
                )
            val realAdapter = PaymentPgConfiguration().fakePgAdapter(RestClient.builder(), properties)

            assertThat(realAdapter.requestPayment(payment)).isInstanceOf(PgRequestResult.Unknown::class.java)
            assertThat(realAdapter.requestPayment(payment.copy(idempotencyKey = "close"))).isInstanceOf(PgRequestResult.Unknown::class.java)
        } finally {
            latch.countDown()
            http.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `설정 문자열에 웹훅 비밀이 드러나지 않는다`() {
        assertThat(PaymentPgProperties(webhookSecret = "secret-value").toString()).doesNotContain("secret-value")
    }
}
