package io.github.ticketing.payment.adapter.required.pg

import com.sun.net.httpserver.HttpServer
import io.github.ticketing.payment.application.port.required.PgPaymentRequest
import io.github.ticketing.payment.application.port.required.PgRequestResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration

class FakePgAdapterTests {
    private val builder = RestClient.builder().baseUrl(BASE_URL)
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val adapter = FakePgAdapter(builder.build())

    private val request = PgPaymentRequest("42", BigDecimal(30_000), "booking-42")

    private fun accepted(body: String = """{"transactionId":"tx-1","status":"PENDING"}""") =
        withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(body)

    @Test
    fun `결제 요청은 멱등 키 헤더와 주문·금액 본문을 보내고 202면 Accepted다`() {
        server.expect(requestTo("$BASE_URL/v1/payments"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Idempotency-Key", "booking-42"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.orderId").value("42"))
            .andExpect(jsonPath("$.amount").value(30000))
            .andRespond(accepted())

        assertThat(adapter.requestPayment(request)).isEqualTo(PgRequestResult.Accepted("tx-1"))
        server.verify()
    }

    @Test
    fun `4xx는 확정적 실패인 Rejected이고 응답의 code를 사유로 쓴다`() {
        server.expect(requestTo("$BASE_URL/v1/payments"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON).body("""{"code":"CARD_DECLINED"}"""))

        assertThat(adapter.requestPayment(request)).isEqualTo(PgRequestResult.Rejected("CARD_DECLINED"))
    }

    @Test
    fun `4xx 본문에 code가 없으면 HTTP 상태를 사유로 쓴다`() {
        server.expect(requestTo("$BASE_URL/v1/payments")).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertThat(adapter.requestPayment(request)).isEqualTo(PgRequestResult.Rejected("HTTP_400"))
    }

    @Test
    fun `5xx는 결과를 모르는 Unknown이다`() {
        server.expect(requestTo("$BASE_URL/v1/payments")).andRespond(withServerError())

        assertThat(adapter.requestPayment(request)).isEqualTo(PgRequestResult.Unknown("HTTP 500"))
    }

    @Test
    fun `202인데 transactionId가 없으면 Unknown이다`() {
        server.expect(requestTo("$BASE_URL/v1/payments")).andRespond(accepted("""{"status":"PENDING"}"""))

        assertThat(adapter.requestPayment(request)).isInstanceOf(PgRequestResult.Unknown::class.java)
    }

    @Test
    fun `환불 요청은 거래 경로와 멱등 키 헤더로 보내고 202면 Accepted다`() {
        server.expect(requestTo("$BASE_URL/v1/payments/tx-1/refund"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Idempotency-Key", "refund-42"))
            .andRespond(accepted())

        assertThat(adapter.requestRefund("tx-1", "refund-42")).isEqualTo(PgRequestResult.Accepted("tx-1"))
        server.verify()
    }

    @Test
    fun `읽기 타임아웃은 Unknown이다`() {
        val slow = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/payments") { exchange ->
                Thread.sleep(2_000)
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            start()
        }
        try {
            val properties = FakePgClientProperties(
                baseUrl = "http://127.0.0.1:${slow.address.port}",
                connectTimeout = Duration.ofSeconds(1),
                readTimeout = Duration.ofMillis(200),
            )
            val result = FakePgAdapter(FakePgClientConfig.fakePgRestClient(properties)).requestPayment(request)

            assertThat(result).isInstanceOf(PgRequestResult.Unknown::class.java)
        } finally {
            slow.stop(0)
        }
    }

    @Test
    fun `연결할 수 없으면 Unknown이다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val properties = FakePgClientProperties(baseUrl = "http://127.0.0.1:$closedPort", connectTimeout = Duration.ofMillis(500))

        val result = FakePgAdapter(FakePgClientConfig.fakePgRestClient(properties)).requestPayment(request)

        assertThat(result).isInstanceOf(PgRequestResult.Unknown::class.java)
    }

    companion object {
        private const val BASE_URL = "http://fake-pg.test"
    }
}
