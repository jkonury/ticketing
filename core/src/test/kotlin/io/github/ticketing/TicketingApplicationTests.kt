package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

class TicketingApplicationTests : IntegrationTestSupport() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `컨텍스트가 뜨고 모듈별 스키마가 생성된다`() {
        val schemas = jdbc.queryForList(
            "select schema_name from information_schema.schemata where schema_name in ('member','catalog','booking','payment','settlement')",
            String::class.java,
        )
        assertEquals(setOf("member", "catalog", "booking", "payment", "settlement"), schemas.toSet())
    }
}
