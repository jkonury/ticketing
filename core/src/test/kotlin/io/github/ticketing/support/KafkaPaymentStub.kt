package io.github.ticketing.support

import io.github.ticketing.KafkaTestSupport
import io.github.ticketing.KafkaTopics
import io.github.ticketing.payment.PaymentFailed
import io.github.ticketing.payment.PaymentRefunded
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.PaymentSucceeded
import io.github.ticketing.payment.RefundRequested
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * core 테스트 전용 Kafka 결제 대역. 결제는 payment 앱이 맡으므로 core 테스트에서는 이 대역이 payment 앱 자리에 선다.
 *
 * - `PaymentRequested`·`RefundRequested` 토픽을 `ticketing-payment`와 다른 그룹 ID로 소비해 [paymentRequests]·[refundRequests]에 기록한다.
 *   실제 payment 앱과 같은 그룹을 쓰면 메시지를 나눠 가지므로 그룹은 인스턴스마다 새로 만든다.
 * - 주문별로 미리 지정한 결과를 결과 토픽에 `orderId` 키, 타입 헤더 없는 JSON으로 발행한다([respondToPayment], [respondToRefund]).
 *   지정하지 않은 주문에는 응답하지 않는다. 응답이 늦게 오는 상황은 테스트가 원하는 시점에 [publishSucceeded] 등을 직접 호출해 만든다.
 * - [publishSucceeded] 등은 같은 결과를 여러 번 호출해 중복 발행할 수 있다(소비자 멱등 검증).
 *
 * 컨텍스트마다 토픽 접두어가 달라 대역도 컨텍스트마다 자기 토픽만 본다. 테스트 사이 상태는 [reset]으로 비운다.
 */
class KafkaPaymentStub(val bootstrapServers: List<String>, val topics: KafkaTopics, private val json: JsonMapper) : SmartLifecycle {
    /** 결제 요청을 받았을 때 보낼 결과. */
    sealed interface PaymentResponse {
        /** 요청 금액 그대로 승인한다. */
        data class Succeeded(val times: Int = 1) : PaymentResponse

        data class Failed(val reason: String = "declined", val times: Int = 1) : PaymentResponse
    }

    val paymentRequests = CopyOnWriteArrayList<PaymentRequested>()
    val refundRequests = CopyOnWriteArrayList<RefundRequested>()

    private val paymentResponses = ConcurrentHashMap<Long, PaymentResponse>()
    private val refundResponses = ConcurrentHashMap<Long, Int>()
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var consumer: KafkaConsumer<String, String>? = null
    private var producer: KafkaProducer<String, String>? = null

    fun paymentRequestsOf(orderId: Long): List<PaymentRequested> = paymentRequests.filter { it.orderId == orderId }

    fun refundRequestsOf(orderId: Long): List<RefundRequested> = refundRequests.filter { it.orderId == orderId }

    /** [orderId]의 `PaymentRequested`가 도착하면 [response]를 발행하도록 지정한다. 요청이 오기 전에 호출해야 한다. */
    fun respondToPayment(orderId: Long, response: PaymentResponse) {
        paymentResponses[orderId] = response
    }

    /** [orderId]의 `RefundRequested`가 도착하면 `PaymentRefunded`를 [times]번 발행하도록 지정한다. */
    fun respondToRefund(orderId: Long, times: Int = 1) {
        refundResponses[orderId] = times
    }

    fun publishSucceeded(
        orderId: Long,
        amount: BigDecimal = BigDecimal(100000),
        paymentId: Long = orderId,
        paidAt: Instant = Instant.now(),
    ) = publish(topics.paymentSucceeded, orderId, PaymentSucceeded(paymentId, orderId, amount, paidAt))

    fun publishFailed(orderId: Long, reason: String = "declined", paymentId: Long = orderId, failedAt: Instant = Instant.now()) =
        publish(topics.paymentFailed, orderId, PaymentFailed(paymentId, orderId, reason, failedAt))

    fun publishRefunded(orderId: Long, paymentId: Long = orderId, refundedAt: Instant = Instant.now()) =
        publish(topics.paymentRefunded, orderId, PaymentRefunded(paymentId, orderId, refundedAt))

    /** [orderId]의 `PaymentRequested`가 대역에 도착할 때까지 기다린다. */
    fun awaitPaymentRequested(orderId: Long): PaymentRequested {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(paymentRequestsOf(orderId)).isNotEmpty()
        }
        return paymentRequestsOf(orderId).first()
    }

    /** [orderId]의 `RefundRequested`가 대역에 도착할 때까지 기다린다. */
    fun awaitRefundRequested(orderId: Long): RefundRequested {
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(refundRequestsOf(orderId)).isNotEmpty()
        }
        return refundRequestsOf(orderId).first()
    }

    /** booking 소비자 그룹이 [resultTopic]의 [orderId] 메시지 [expected]건을 모두 커밋했고 DLT에는 아무것도 없음을 확인한다. */
    fun awaitResultsConsumedByBooking(resultTopic: String, orderId: Long, expected: Int) {
        KafkaTestSupport.awaitConsumed(bootstrapServers, resultTopic, orderId.toString(), BOOKING_GROUP, expected)
        assertThat(KafkaTestSupport.recordsOf(bootstrapServers, topics.deadLetterOf(resultTopic), orderId.toString())).isEmpty()
    }

    fun reset() {
        paymentRequests.clear()
        refundRequests.clear()
        paymentResponses.clear()
        refundResponses.clear()
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val kafkaConsumer = KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "payment-stub-${UUID.randomUUID()}",
                // 토픽은 컨텍스트별 접두어로 격리되어 있어 처음부터 읽어도 다른 테스트의 메시지가 섞이지 않는다
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
            StringDeserializer(),
            StringDeserializer(),
        )
        kafkaConsumer.subscribe(listOf(topics.paymentRequested, topics.refundRequested))
        consumer = kafkaConsumer
        producer = KafkaProducer(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.LINGER_MS_CONFIG to 0,
            ),
            StringSerializer(),
            StringSerializer(),
        )
        worker = Thread({ pollLoop(kafkaConsumer) }, "kafka-payment-stub").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        consumer?.wakeup()
        worker?.join(Duration.ofSeconds(10))
        producer?.close(Duration.ofSeconds(5))
    }

    override fun isRunning(): Boolean = running.get()

    private fun pollLoop(kafkaConsumer: KafkaConsumer<String, String>) {
        try {
            while (running.get()) {
                kafkaConsumer.poll(Duration.ofMillis(200)).forEach { record ->
                    runCatching { handle(record.topic(), record.value()) }
                        .onFailure { log.warn("결제 대역이 메시지를 처리하지 못했다. topic={}", record.topic(), it) }
                }
            }
        } catch (_: WakeupException) {
            // stop()이 깨운 것이다
        } finally {
            kafkaConsumer.close(Duration.ofSeconds(5))
        }
    }

    private fun handle(topic: String, value: String) {
        when (topic) {
            topics.paymentRequested -> {
                val request = json.readValue(value, PaymentRequested::class.java)
                paymentRequests += request
                when (val response = paymentResponses[request.orderId]) {
                    is PaymentResponse.Succeeded -> repeat(response.times) { publishSucceeded(request.orderId, request.amount) }
                    is PaymentResponse.Failed -> repeat(response.times) { publishFailed(request.orderId, response.reason) }
                    null -> Unit
                }
            }

            topics.refundRequested -> {
                val request = json.readValue(value, RefundRequested::class.java)
                refundRequests += request
                repeat(refundResponses[request.orderId] ?: 0) { publishRefunded(request.orderId) }
            }
        }
    }

    // 타입 헤더 없이 JSON 문자열만 보낸다. 실제 payment 앱의 외부화와 같은 형식이다(ADR 0043)
    private fun publish(topic: String, orderId: Long, event: Any) {
        val sender = checkNotNull(producer) { "결제 대역이 아직 시작되지 않았다" }
        sender.send(ProducerRecord(topic, orderId.toString(), json.writeValueAsString(event))).get()
    }

    companion object {
        const val BOOKING_GROUP = "ticketing-booking"
    }
}
