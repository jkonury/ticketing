package io.github.ticketing.catalog

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.catalog.query.CatalogQueryRepository
import io.github.ticketing.catalog.seed.CatalogSeedRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("seed")
class CatalogSeedRunnerTests : IntegrationTestSupport() {

    @Autowired lateinit var runner: CatalogSeedRunner

    @Autowired lateinit var queries: CatalogQueryRepository

    private fun seededTitles() = queries.findPerformances(null, 100).items.filter { it.title.startsWith("시드 ") }

    @Test
    fun `시드를 실행하면 공연 3편이 생기고 다시 실행해도 늘지 않는다`() {
        // seed 프로필이라 컨텍스트 기동 시 한 번 실행되었다. 다시 실행해도 같아야 한다
        assertThat(seededTitles()).hasSize(3)

        runner.run(DefaultApplicationArguments())

        val seeded = seededTitles()

        assertThat(seeded).hasSize(3)
        assertThat(seeded).allMatch { it.firstStartsAt != null }

        val detail = requireNotNull(queries.findPerformanceDetail(seeded.first().id))
        val seatLayout = requireNotNull(queries.findSeatLayout(detail.schedules.first().id))

        assertThat(detail.schedules).hasSize(3)
        assertThat(seatLayout.seats).hasSize(800)
    }
}
