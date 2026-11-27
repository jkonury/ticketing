package io.github.ticketing.settlement

import io.github.ticketing.KafkaTopics
import io.github.ticketing.SETTLEMENT_TEST_JWT_SECRET
import io.github.ticketing.SettlementTestContainers
import io.github.ticketing.SettlementTestDatabases
import io.github.ticketing.SettlementTestDatabases.CORE_DATABASE
import io.github.ticketing.SettlementTestDatabases.CORE_USER
import io.github.ticketing.SettlementTestDatabases.TEST_PASSWORD
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.kafka.core.KafkaTemplate
import ticketing.jooq.settlement.tables.references.BOOKING_REFUND
import ticketing.jooq.settlement.tables.references.BOOKING_SALE
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["ticketing.jwt.secret=$SETTLEMENT_TEST_JWT_SECRET"])
@Import(SettlementTestContainers::class, SettlementKafkaIntegrationTests.FixedClock::class)
class SettlementKafkaIntegrationTests(
    private val dsl: DSLContext,
    private val kafka: KafkaTemplate<String, ByteArray>,
    private val topics: KafkaTopics,
    private val json: JsonMapper,
    private val kafkaProperties: KafkaProperties,
    private val environment: Environment,
) {
    private val now = Instant.parse("2026-11-10T00:00:00Z")

    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(RECEIVED_AT, ZoneOffset.UTC)
    }

    @Test
    fun `Kafka 사본은 중복과 순서 역전에도 예매별로 한 건씩 기록된다`() {
        val id = 18001L
        val cancelled = BookingCancelled(id, 2, 3, 5, 6, "SEAT_ALREADY_SOLD", true, now)
        val confirmed = BookingConfirmed(id, 2, 3, 5, 6, listOf(4), BigDecimal("8"), BigDecimal.TEN, BigDecimal("2"), now)

        repeat(2) { publish(topics.bookingCancelled, id, cancelled) }
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(dsl.fetchCount(BOOKING_REFUND, BOOKING_REFUND.BOOKING_ID.eq(id))).isEqualTo(1)
        }
        assertThat(dsl.fetchCount(BOOKING_SALE, BOOKING_SALE.BOOKING_ID.eq(id))).isZero()

        val saleOffset = publish(topics.bookingConfirmed, id, confirmed)
        val refundOffset = publish(topics.bookingCancelled, id, cancelled)
        val duplicateSaleOffset = publish(topics.bookingConfirmed, id, confirmed)
        awaitConsumed(saleOffset, refundOffset, duplicateSaleOffset)

        val sale = dsl.selectFrom(BOOKING_SALE).where(BOOKING_SALE.BOOKING_ID.eq(id)).fetchSingle()
        assertThat(sale.performanceId).isEqualTo(confirmed.performanceId)
        assertThat(sale.organizerId).isEqualTo(confirmed.organizerId)
        assertThat(sale.scheduleId).isEqualTo(confirmed.scheduleId)
        assertThat(sale.amount).isEqualTo(8L)
        assertThat(sale.listAmount).isEqualTo(10L)
        assertThat(sale.discountAmount).isEqualTo(2L)
        assertThat(sale.confirmedAt?.toInstant()).isEqualTo(now)
        assertThat(sale.receivedAt?.toInstant()).isEqualTo(RECEIVED_AT)

        val refund = dsl.selectFrom(BOOKING_REFUND).where(BOOKING_REFUND.BOOKING_ID.eq(id)).fetchSingle()
        assertThat(refund.performanceId).isEqualTo(cancelled.performanceId)
        assertThat(refund.organizerId).isEqualTo(cancelled.organizerId)
        assertThat(refund.reason).isEqualTo(cancelled.reason)
        assertThat(refund.cancelledAt?.toInstant()).isEqualTo(now)
        assertThat(refund.receivedAt?.toInstant()).isEqualTo(RECEIVED_AT)
    }

    @Test
    fun `환불이 필요 없는 취소는 소비하되 사본은 남기지 않는다`() {
        val id = 18002L
        val event = BookingCancelled(id, 2, 3, 5, 6, "PAYMENT_FAILED", false, now)

        awaitConsumed(publish(topics.bookingCancelled, id, event))

        assertThat(dsl.fetchCount(BOOKING_REFUND, BOOKING_REFUND.BOOKING_ID.eq(id))).isZero()
    }

    @Test
    fun `잘못된 본문은 원래 키와 함께 DLT에 남는다`() {
        val key = "18003"
        val invalid = "{broken}"
        val dlt = TopicPartition(topics.deadLetterOf(topics.bookingConfirmed), 0)
        KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaProperties.bootstrapServers,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ),
            StringDeserializer(),
            StringDeserializer(),
        ).use { consumer ->
            consumer.assign(listOf(dlt))
            consumer.seekToBeginning(listOf(dlt))
            kafka.send(topics.bookingConfirmed, key, invalid.toByteArray()).get(10, TimeUnit.SECONDS)

            await().atMost(Duration.ofSeconds(15)).untilAsserted {
                val records = consumer.poll(Duration.ofMillis(200)).filter { it.key() == key }
                assertThat(records).singleElement().satisfies({ record -> assertThat(record.value()).isEqualTo(invalid) })
            }
        }
    }

    @Test
    fun `정산과 core의 앱 계정은 각자의 DB에만 접속한다`() {
        val settlementUser = environment.getRequiredProperty("spring.datasource.username")
        val settlementUrl = environment.getRequiredProperty("spring.datasource.url")
        val coreUrl = SettlementTestDatabases.jdbcUrl(CORE_DATABASE)

        assertThat(dsl.fetchOne("select current_user")?.get(0)).isEqualTo(settlementUser)
        DriverManager.getConnection(coreUrl, CORE_USER, TEST_PASSWORD).use { assertThat(it.isValid(5)).isTrue() }
        assertThatExceptionOfType(SQLException::class.java)
            .isThrownBy { DriverManager.getConnection(coreUrl, settlementUser, TEST_PASSWORD).close() }
            .satisfies({ error -> assertThat(error.sqlState).isEqualTo("42501") })
        assertThatExceptionOfType(SQLException::class.java)
            .isThrownBy { DriverManager.getConnection(settlementUrl, CORE_USER, TEST_PASSWORD).close() }
            .satisfies({ error -> assertThat(error.sqlState).isEqualTo("42501") })
    }

    private fun publish(topic: String, id: Long, event: Any): RecordMetadata =
        kafka.send(topic, id.toString(), json.writeValueAsBytes(event)).get(10, TimeUnit.SECONDS).recordMetadata

    private fun awaitConsumed(vararg records: RecordMetadata) {
        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaProperties.bootstrapServers)).use { admin ->
            await().atMost(Duration.ofSeconds(15)).untilAsserted {
                val committed = admin.listConsumerGroupOffsets("ticketing-settlement").partitionsToOffsetAndMetadata().get()
                records.forEach { record ->
                    assertThat(committed[TopicPartition(record.topic(), record.partition())]?.offset()).isGreaterThan(record.offset())
                }
            }
        }
    }

    companion object {
        private val RECEIVED_AT = Instant.parse("2026-11-11T00:00:00Z")
    }
}
