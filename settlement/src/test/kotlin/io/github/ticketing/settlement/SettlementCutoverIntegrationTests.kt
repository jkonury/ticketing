package io.github.ticketing.settlement

import io.github.ticketing.KafkaTopics
import io.github.ticketing.SettlementTestDatabases
import io.github.ticketing.SettlementWebTestSupport
import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

@TestPropertySource(properties = ["spring.kafka.listener.auto-startup=false"])
class SettlementCutoverIntegrationTests(
    private val dsl: DSLContext,
    private val mvc: MockMvcTester,
    private val environment: Environment,
    private val kafkaProperties: KafkaProperties,
    private val kafka: KafkaTemplate<String, ByteArray>,
    private val json: JsonMapper,
    private val topics: KafkaTopics,
    private val listeners: KafkaListenerEndpointRegistry,
) : SettlementWebTestSupport() {
    @TempDir
    lateinit var tempDir: Path
    private lateinit var sourceDb: String
    private val repository = Path.of("..").toAbsolutePath().normalize()

    @BeforeEach
    fun prepareSourceAndTarget() {
        sourceDb = SettlementTestDatabases.newLegacyDatabase()
        dsl.execute("TRUNCATE settlement.booking_sale, settlement.booking_refund, settlement.daily_settlement")
        withSource { source ->
            source.execute("CREATE SCHEMA settlement")
            val migrations = repository.resolve("core/src/main/resources/db/migration")
            listOf(
                "V2__create_event_publication.sql",
                "V7__create_batch_metadata.sql",
                "V8__create_settlement_copy_tables.sql",
                "V9__create_daily_settlement.sql",
                "V14__add_settlement_list_price_columns.sql",
            ).forEach { source.execute(migrations.resolve(it).readText()) }
            source.execute(
                """
                INSERT INTO settlement.booking_sale
                VALUES (41, 51, 61, 71, 8000, '2026-11-10 08:00:00+09', '2026-11-10 08:01:00+09', 10000, 2000);
                INSERT INTO settlement.booking_refund
                VALUES (41, 51, 61, 'REFUND', '2026-11-11 08:00:00+09', '2026-11-11 08:01:00+09');
                INSERT INTO settlement.daily_settlement
                VALUES ('2026-11-10', 51, 61, 3, 30000, 1, 10000, 20000, 0.10, 2000, 18000, '2026-11-12 08:00:00+09', 3000);
                INSERT INTO batch.batch_job_instance (job_instance_id, job_name, job_key)
                VALUES (81, 'dailySettlementJob', 'old-job-key');
                INSERT INTO batch.batch_job_execution (job_execution_id, job_instance_id, create_time, status, exit_code)
                VALUES (91, 81, '2026-11-12 08:00:00', 'COMPLETED', 'COMPLETED');
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `세 테이블을 복사하고 재생을 마친 뒤에도 과거 정산과 원본 이력이 유지된다`() {
        val moved = migrate()
        assertThat(moved.first).withFailMessage(moved.second).isZero()
        assertThat(moved.second).contains("booking_sale|1", "booking_refund|1", "daily_settlement|1", "SHA-256")
        val retried = migrate()
        assertThat(retried.first).withFailMessage(retried.second).isZero()
        assertThat(retried.second).contains("대상에 데이터가 있어 적재 없이 대조합니다.")

        replayOverlappingRecords()

        val verified = migrate("--verify-only")
        assertThat(verified.first).withFailMessage(verified.second).isZero()
        assertThat(verified.second.substringAfter("정산 대조 일치")).isEqualTo(moved.second.substringAfter("정산 대조 일치"))
        mvc.get().uri("/api/v1/settlements?from=2026-11-10&to=2026-11-10")
            .header("Authorization", "Bearer ${token(61)}").assertThat().hasStatus(HttpStatus.OK).bodyJson().apply {
                extractingPath("$.length()").isEqualTo(1)
                extractingPath("$[0].performanceId").isEqualTo(51)
                extractingPath("$[0].payoutAmount").isEqualTo(18000)
            }
        withSource { source ->
            assertThat(source.fetchValue("select status from batch.batch_job_execution where job_execution_id = 91")).isEqualTo("COMPLETED")
            assertThat(source.fetchValue("select payout_amount from settlement.daily_settlement")).isEqualTo(BigDecimal("18000"))
            val restored = source.execute(
                "insert into settlement.booking_refund select 42, performance_id, organizer_id, reason, cancelled_at, received_at " +
                    "from settlement.booking_refund where booking_id = 41",
            )
            assertThat(restored).isEqualTo(1)
        }
        assertThat(dsl.fetchValue("select count(*) from batch.batch_job_execution")).isEqualTo(0L)
        assertThat(dsl.fetchValue("select count(*) from settlement.booking_refund")).isEqualTo(1L)
    }

    @Test
    fun `대상이 이미 채워져 있고 원본과 다르면 덮어쓰지 않는다`() {
        dsl.execute("insert into settlement.booking_refund values (42, 51, 61, 'EXISTING', now(), now())")

        val result = migrate()

        assertThat(result.first).isNotZero()
        assertThat(result.second).contains("원본과 대상의 건수 또는 정산 내용이 다릅니다")
        assertThat(dsl.fetchValue("select booking_id from settlement.booking_refund")).isEqualTo(42L)
        assertThat(dsl.fetchValue("select count(*) from settlement.booking_sale")).isEqualTo(0L)
    }

    @Test
    fun `결과 적재에 실패하면 이미 복사한 두 사본도 롤백한다`() {
        dsl.execute("alter table settlement.daily_settlement add constraint fail_load check (payout_amount <> 18000)")
        try {
            val result = migrate()

            assertThat(result.first).isNotZero()
            assertThat(result.second).contains("fail_load")
            assertThat(dsl.fetchValue("select count(*) from settlement.booking_sale")).isEqualTo(0L)
            assertThat(dsl.fetchValue("select count(*) from settlement.booking_refund")).isEqualTo(0L)
            assertThat(dsl.fetchValue("select count(*) from settlement.daily_settlement")).isEqualTo(0L)
        } finally {
            dsl.execute("alter table settlement.daily_settlement drop constraint fail_load")
        }
    }

    @Test
    fun `정산 발행 또는 실행을 배출할 때까지 이전을 중단한다`() {
        withSource { source ->
            source.execute(
                """
                INSERT INTO public.event_publication (id, listener_id, event_type, serialized_event, publication_date)
                VALUES (gen_random_uuid(), 'settlement', 'io.github.ticketing.booking.BookingCancelled', '{}', now());
                """.trimIndent(),
            )
        }
        val pending = migrate()
        assertThat(pending.first).isNotZero()
        assertThat(pending.second).contains("미완료 정산 관련 발행 또는 실행 중인 일별 정산 배치")
        withSource { source ->
            source.execute("update public.event_publication set completion_date = now()")
            source.execute("update batch.batch_job_execution set status = 'STARTED'")
        }
        val active = migrate()
        assertThat(active.first).isNotZero()
        assertThat(active.second).contains("미완료 정산 관련 발행 또는 실행 중인 일별 정산 배치")
        assertThat(dsl.fetchValue("select count(*) from settlement.booking_sale")).isEqualTo(0L)
    }

    private fun replayOverlappingRecords() {
        val cancelledAt = Instant.parse("2026-11-10T23:00:00Z")
        val confirmedAt = Instant.parse("2026-11-09T23:00:00Z")
        val cancel = BookingCancelled(41, 1, 71, 51, 61, "REFUND", true, cancelledAt)
        val confirm =
            BookingConfirmed(41, 1, 71, 51, 61, listOf(1), BigDecimal("8000"), BigDecimal("10000"), BigDecimal("2000"), confirmedAt)
        val events = mapOf(topics.bookingCancelled to cancel, topics.bookingConfirmed to confirm)
        events.forEach { (topic, event) -> kafka.send(topic, "41", json.writeValueAsBytes(event)).get(10, TimeUnit.SECONDS) }

        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaProperties.bootstrapServers)).use { admin ->
            val watermarks = endOffsets(admin, events.keys, KafkaTopics.PARTITIONS)
            assertThat(watermarks.values).contains(1L)
            listeners.start()
            try {
                await().atMost(Duration.ofSeconds(15)).untilAsserted {
                    val committed = admin.listConsumerGroupOffsets("ticketing-settlement").partitionsToOffsetAndMetadata().get()
                    watermarks.filterValues { it > 0 }.forEach { (partition, offset) ->
                        assertThat(committed[partition]?.offset()).isGreaterThanOrEqualTo(offset)
                    }
                }
                val dltTopics = events.keys.map(topics::deadLetterOf)
                assertThat(endOffsets(admin, dltTopics, 1).values).containsOnly(0L)
            } finally {
                listeners.stop()
            }
        }
    }

    private fun endOffsets(admin: Admin, topics: Collection<String>, partitions: Int): Map<TopicPartition, Long> {
        val offsets = topics.flatMap { topic -> (0 until partitions).map { TopicPartition(topic, it) } }
            .associateWith { OffsetSpec.latest() }
        return admin.listOffsets(offsets).all().get().mapValues { it.value.offset() }
    }

    private fun migrate(vararg flags: String): Pair<Int, String> {
        val targetDb = environment.getRequiredProperty("spring.datasource.url").substringAfterLast('/')
        val targetUser = environment.getRequiredProperty("spring.datasource.username")
        val output = tempDir.resolve("migration.log")
        val process = ProcessBuilder(
            listOf(
                "./scripts/db/migrate-settlement-data.sh",
                "--postgres-container",
                SettlementTestDatabases.containerId,
                "--source-db",
                sourceDb,
                "--target-db",
                targetDb,
            ) + flags,
        ).directory(repository.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).apply {
            environment()["TICKETING_SETTLEMENT_POSTGRES_USER"] = targetUser
            environment()["TICKETING_SETTLEMENT_POSTGRES_PASSWORD"] = SettlementTestDatabases.TEST_PASSWORD
        }.start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "정산 이전 시간 초과".also { process.destroyForcibly() } }
        return process.exitValue() to output.readText()
    }

    private fun <T> withSource(block: (DSLContext) -> T): T =
        SettlementTestDatabases.adminConnection(sourceDb).use { block(DSL.using(it, SQLDialect.POSTGRES)) }
}
