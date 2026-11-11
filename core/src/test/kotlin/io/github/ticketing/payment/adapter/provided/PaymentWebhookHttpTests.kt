package io.github.ticketing.payment.adapter.provided

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.booking.adapter.provided.event.PaymentResultListener
import io.github.ticketing.payment.application.port.required.PaymentCommandPort
import io.github.ticketing.payment.domain.MemberId
import io.github.ticketing.payment.domain.OrderId
import io.github.ticketing.payment.domain.Payment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@AutoConfigureMockMvc
@Import(PaymentWebhookHttpTests.FixedClock::class)
class PaymentWebhookHttpTests : IntegrationTestSupport() {
    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean @Primary fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC)
    }
    @MockitoBean lateinit var bookingListener: PaymentResultListener
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var payments: PaymentCommandPort
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    private val now = Instant.parse("2026-11-11T00:00:00Z")
    private val path = "/api/v1/payments/webhooks/fake-pg"
    private fun body(event: String, order: Long, transaction: String, amount: String = "100") =
        """{"eventId":"$event","type":"PAYMENT_APPROVED","transactionId":"$transaction","orderId":"$order","amount":$amount,"occurredAt":"$now","failureReason":null}"""
    private fun sign(timestamp: String, raw: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("test-webhook-secret".toByteArray(), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal("$timestamp.$raw".toByteArray()))
    }
    private fun send(raw: String, time: String = now.epochSecond.toString(), signature: String = sign(time, raw)) =
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-FakePg-Timestamp", time).header("X-FakePg-Signature", signature).content(raw))
    private fun payment(order: Long, transaction: String) {
        tx.execute { val p = Payment.request(OrderId(order), MemberId(1), BigDecimal("100"), UUID.randomUUID().toString(), now); p.attachPgTransaction(transaction); payments.save(p) }
    }
    @Test fun `정상 웹훅과 중복은 성공하고 계약 오류와 미등록 거래 기록은 롤백된다`() {
        val order = System.nanoTime(); val transaction = UUID.randomUUID().toString(); val event = UUID.randomUUID().toString()
        payment(order, transaction)
        val raw = body(event, order, transaction)
        send(raw).andExpect(status().isOk); send(raw).andExpect(status().isOk)
        send(raw.replace(event, UUID.randomUUID().toString()).replace("PAYMENT_APPROVED", "PAYMENT_FAILED").replace("\"failureReason\":null", "\"failureReason\":\"declined\"" )).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATE"))
        val mismatch = UUID.randomUUID().toString()
        send(body(mismatch, order, transaction, "101")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PAYMENT_CONTRACT_MISMATCH"))
        val missing = UUID.randomUUID().toString()
        send(body(missing, order + 1, UUID.randomUUID().toString())).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
        assertThat(jdbc.queryForObject("select count(*) from payment.webhook_event where event_id in (?, ?)", Int::class.java, mismatch, missing)).isZero()
    }
    @Test fun `서명은 원문과 시간 허용 경계를 검증하고 파싱보다 먼저 수행된다`() {
        val raw = "{bad"
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"))
        send(raw, signature = "xx").andExpect(status().isUnauthorized)
        send(raw, signature = "00".repeat(32)).andExpect(status().isUnauthorized)
        send(raw, time = "999999999999999999999999").andExpect(status().isUnauthorized)
        send(raw, time = (now.epochSecond - 301).toString()).andExpect(status().isUnauthorized)
        send(raw, time = (now.epochSecond + 301).toString()).andExpect(status().isUnauthorized)
        send(raw, time = (now.epochSecond - 300).toString()).andExpect(status().isBadRequest)
        send(raw, time = (now.epochSecond + 300).toString()).andExpect(status().isBadRequest)
        send(raw + " ", signature = sign(now.epochSecond.toString(), raw)).andExpect(status().isUnauthorized)
        send(raw).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_WEBHOOK"))
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/payments/webhooks/fake-pg/other")).andExpect(status().isUnauthorized)
    }
    @Test fun `서명된 잘못된 필드와 타입은 입력 오류다`() {
        val valid = body("event", 1, "tx")
        listOf(valid + " {}", valid + " trailing", valid.replace("\"amount\":100", "\"amount\":1.2"), valid.replace("\"orderId\":\"1\"", "\"orderId\":1"), valid.replace("\"event\"", "\"${"x".repeat(65)}\""), valid.replace("PAYMENT_APPROVED", "UNKNOWN"), valid.replace("\"amount\":100", "\"amount\":0"), valid.replace("\"orderId\":\"1\"", "\"orderId\":\"0\"")).forEach {
            send(it).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_WEBHOOK"))
        }
    }
}
