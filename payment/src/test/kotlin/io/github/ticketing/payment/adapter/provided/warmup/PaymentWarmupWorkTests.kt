package io.github.ticketing.payment.adapter.provided.warmup

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.TestPg
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

@TestPropertySource(
    properties = [
        "ticketing.startup-warmup.enabled=true",
        "ticketing.startup-warmup.adapter-iterations=3",
    ],
)
internal class PaymentWarmupWorkTests(private val work: PaymentWarmupWork, private val jdbc: JdbcTemplate, private val testPg: TestPg) :
    IntegrationTestSupport() {
    private fun count(table: String): Int = requireNotNull(jdbc.queryForObject("select count(*) from $table", Int::class.java))

    @Test
    fun `결제 준비 웹훅 승인 PG 조회를 실제 경로로 거치고 DB와 이벤트 레지스트리에 아무것도 남기지 않는다`() {
        assertThat(work.pgProbes).isEqualTo(3)
        assertThat(listOf("payment.payment", "payment.webhook_event", "public.event_publication").map(::count))
            .containsOnly(0)
        assertThat(testPg.requests).isEmpty()
        assertThat(testPg.refunds).isEmpty()
    }
}
