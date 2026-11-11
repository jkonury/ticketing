package io.github.ticketing

import io.github.ticketing.payment.application.port.required.PgTransaction
import io.github.ticketing.payment.application.port.required.PgTransactionQueryPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.LocalDate

@Import(BatchIntegrationTestSupport.TestConfig::class)
@ActiveProfiles("batch")
@TestPropertySource(properties = ["spring.batch.job.enabled=false"])
abstract class BatchIntegrationTestSupport : IntegrationTestSupport() {
    @TestConfiguration(proxyBeanMethods = false)
    class TestConfig {
        @Bean
        @Primary
        fun testPgTransactions(): TestPgTransactions = TestPgTransactions()
    }
}

class TestPgTransactions : PgTransactionQueryPort {
    var transactions: List<PgTransaction> = emptyList()
    var fail = false

    override fun findTransactions(date: LocalDate): List<PgTransaction> {
        check(!fail) { "의도된 PG 조회 실패" }
        return transactions
    }
}
