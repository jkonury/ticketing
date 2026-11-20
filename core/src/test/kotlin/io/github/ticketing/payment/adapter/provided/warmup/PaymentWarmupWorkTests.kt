package io.github.ticketing.payment.adapter.provided.warmup

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.TestPg
import io.github.ticketing.payment.PaymentWarmupRequested
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@TestPropertySource(
    properties = [
        "ticketing.startup-warmup.enabled=true",
        "ticketing.startup-warmup.local-iterations=0",
        "ticketing.startup-warmup.adapter-iterations=0",
    ],
)
internal class PaymentWarmupWorkTests(
    private val work: PaymentWarmupWork,
    private val jdbc: JdbcTemplate,
    private val testPg: TestPg,
    private val publisher: ApplicationEventPublisher,
) : IntegrationTestSupport() {
    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("select count(*) from $table", Int::class.java))

    @Test
    fun `결제 준비 웹훅 승인 PG 조회를 실제 경로로 거치고 DB와 이벤트 레지스트리에 아무것도 남기지 않는다`() {
        val before = listOf("payment.payment", "payment.webhook_event", "event_publication").map(::count)
        val probes = work.pgProbes

        repeat(3) { work.run() }

        assertThat(listOf("payment.payment", "payment.webhook_event", "event_publication").map(::count)).isEqualTo(before)
        assertThat(work.pgProbes).isEqualTo(probes + 3)
        assertThat(testPg.requests).isEmpty()
        assertThat(testPg.refunds).isEmpty()
    }

    @Test
    fun `예열 이벤트를 받으면 같은 처리를 실행한다`() {
        val probes = work.pgProbes

        publisher.publishEvent(PaymentWarmupRequested())

        assertThat(work.pgProbes).isEqualTo(probes + 1)
    }
}
