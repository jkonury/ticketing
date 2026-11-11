package io.github.ticketing.catalog

import io.github.ticketing.catalog.domain.Performance
import io.github.ticketing.catalog.domain.SeatGrade
import io.github.ticketing.catalog.domain.Venue
import io.github.ticketing.catalog.domain.VenueSeat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class CatalogDomainTests {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `좌석이 없는 공연장은 만들 수 없다`() {
        assertThatThrownBy { Venue("공연장", emptyList(), now) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `좌석 키가 중복되면 공연장을 만들 수 없다`() {
        val seats = listOf(VenueSeat("A", "1", 1, SeatGrade.VIP), VenueSeat("A", "1", 1, SeatGrade.R))

        assertThatThrownBy { Venue("공연장", seats, now) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `좌석이 유효하면 공연장을 만든다`() {
        val venue = Venue("공연장", listOf(VenueSeat("A", "1", 1, SeatGrade.VIP), VenueSeat("A", "1", 2, SeatGrade.VIP)), now)

        assertThat(venue.seats).hasSize(2)
    }

    @Test
    fun `음수 가격은 허용하지 않는다`() {
        assertThatThrownBy { Performance(1, "제목", "설명", 1, mapOf(SeatGrade.VIP to BigDecimal(-1)), now) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `가격표가 비어 있으면 공연을 만들 수 없다`() {
        assertThatThrownBy { Performance(1, "제목", "설명", 1, emptyMap(), now) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `가격이 0이어도 공연을 만든다`() {
        val performance = Performance(1, "제목", "설명", 1, mapOf(SeatGrade.A to BigDecimal.ZERO), now)

        assertThat(performance.prices).containsEntry(SeatGrade.A, BigDecimal.ZERO)
    }
}
