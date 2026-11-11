package io.github.ticketing.fakepg.payment

import com.sun.net.httpserver.HttpServer
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 테스트에서 시각을 직접 움직이는 Clock */
class MutableClock(@Volatile private var now: Instant) : Clock() {
    fun set(instant: Instant) {
        now = instant
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

@TestConfiguration(proxyBeanMethods = false)
class TestClockConfig {
    @Bean
    @Primary
    fun testClock(): MutableClock = MutableClock(DEFAULT_NOW)

    companion object {
        val DEFAULT_NOW: Instant = Instant.parse("2030-01-01T12:00:00Z")
    }
}

data class ReceivedWebhook(
    val rawBody: String,
    val timestamp: String?,
    val signature: String?,
    val statusReturned: Int,
    val json: JsonNode,
) {
    val eventId: String get() = json.get("eventId").asString()
    val type: String get() = json.get("type").asString()
    val orderId: String get() = json.get("orderId").asString()
}

/** JVM 안에서 하나만 띄우는 가짜 웹훅 수신기. 늦게 도착하는 이전 테스트의 웹훅은 orderId로 걸러낸다. */
object TestWebhookReceiver {
    private val mapper = JsonMapper.builder().build()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
        server.createContext("/webhook") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val json = mapper.readTree(body)
            val orderId = json.get("orderId").asString()
            val remaining = failures.computeIfAbsent(orderId) { AtomicInteger(0) }
            val status = if (remaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) 500 else 200
            received.add(
                ReceivedWebhook(
                    body,
                    exchange.requestHeaders.getFirst("X-FakePg-Timestamp"),
                    exchange.requestHeaders.getFirst("X-FakePg-Signature"),
                    status,
                    json,
                ),
            )
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        server.start()
    }

    private val received = CopyOnWriteArrayList<ReceivedWebhook>()
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    val url: String get() = "http://127.0.0.1:${server.address.port}/webhook"

    fun forOrder(orderId: String): List<ReceivedWebhook> = received.filter { it.orderId == orderId }

    /** 해당 주문의 다음 [times]번 수신에 500을 돌려준다 */
    fun failNext(orderId: String, times: Int) {
        failures[orderId] = AtomicInteger(times)
    }
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig::class)
abstract class FakePgTestSupport(protected val mockMvc: MockMvc, protected val clock: MutableClock) {

    protected val mapper: JsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun resetClock() {
        clock.set(TestClockConfig.DEFAULT_NOW)
    }

    protected fun requestPayment(
        orderId: String,
        key: String = "key-$orderId",
        amount: Long = 12000,
        scenario: String? = null,
    ): ResultActions {
        val scenarioJson = scenario?.let { ""","scenario":"$it"""" } ?: ""
        return mockMvc.perform(
            post("/v1/payments")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"orderId":"$orderId","amount":$amount$scenarioJson}"""),
        )
    }

    protected fun transactionIdOf(result: ResultActions): String =
        mapper.readTree(result.andReturn().response.contentAsString).get("transactionId").asString()

    protected fun listTransactions(date: String): List<JsonNode> =
        mapper.readTree(mockMvc.perform(get("/v1/transactions").param("date", date)).andReturn().response.contentAsString).toList()

    protected fun statusOf(transactionId: String, date: String = "2030-01-01"): String? =
        listTransactions(date).firstOrNull { it.get("transactionId").asString() == transactionId }?.get("status")?.asString()

    protected fun awaitWebhooks(orderId: String, count: Int, timeout: Duration = Duration.ofSeconds(5)): List<ReceivedWebhook> {
        await.atMost(timeout).untilAsserted {
            kotlin.test.assertTrue(TestWebhookReceiver.forOrder(orderId).size >= count, "웹훅 $count 건 대기 중")
        }
        return TestWebhookReceiver.forOrder(orderId)
    }

    /** 더 이상 웹훅이 오지 않음을 확인한다. 재시도 간격(50ms)보다 충분히 길게 관찰한다. */
    protected fun assertNoMoreWebhooks(orderId: String, expected: Int) {
        await.during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(3)).untilAsserted {
            kotlin.test.assertEquals(expected, TestWebhookReceiver.forOrder(orderId).size)
        }
    }

    protected fun expectedSignature(webhook: ReceivedWebhook): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec("test-webhook-secret".toByteArray(), "HmacSHA256"))
        return java.util.HexFormat.of().formatHex(mac.doFinal("${webhook.timestamp}.${webhook.rawBody}".toByteArray()))
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun webhookUrl(registry: DynamicPropertyRegistry) {
            registry.add("fake-pg.webhook.url") { TestWebhookReceiver.url }
        }
    }
}
