package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

class TicketingApplicationTests(private val jdbc: JdbcTemplate) : IntegrationTestSupport() {

    @Test
    fun `core 기동 후 core가 사용하는 스키마만 남는다`() {
        val schemas = jdbc.queryForList(
            """
            select schema_name from information_schema.schemata
            where schema_name in ('member', 'catalog', 'booking', 'payment', 'settlement', 'coupon', 'audit', 'batch')
            """.trimIndent(),
            String::class.java,
        )
        assertEquals(setOf("member", "catalog", "booking", "settlement", "coupon", "audit", "batch"), schemas.toSet())
    }
}
