package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.junit.jupiter.api.Assertions.assertEquals

class DatabaseIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `마이그레이션이 모듈 스키마를 생성한다`() {
        val count = jdbc.queryForObject("select count(*) from information_schema.schemata where schema_name in ('member', 'catalog', 'booking', 'payment', 'settlement')", Int::class.java)
        assertEquals(5, count)
    }
}
