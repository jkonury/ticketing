package io.github.ticketing.realtime.seat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SeatLogicTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)

    @Test
    fun `만료된 선점은 사용 가능하고 유효한 선점은 만료 시각을 포함한다`() {
        assertThat(snapshot("1", "HELD:999", clock)).isEqualTo(SeatSnapshot(1, "AVAILABLE"))
        assertThat(snapshot("2", "HELD:1000", clock)).isEqualTo(SeatSnapshot(2, "HELD", 1000))
        assertThat(snapshot("3", "SOLD", clock)).isEqualTo(SeatSnapshot(3, "SOLD"))
    }

    @Test
    fun `스트림 식별자는 숫자 순서로 비교하고 범위 밖이나 잘못된 형식을 거부한다`() {
        assertThat(requireNotNull(StreamId.parse("10-0")).compareTo(requireNotNull(StreamId.parse("9-99")))).isPositive()
        assertThat(requireNotNull(StreamId.parse("1-2")).compareTo(requireNotNull(StreamId.parse("1-1")))).isPositive()
        assertThat(StreamId.parse("18446744073709551616-0")).isNull()
        assertThat(StreamId.parse("1-x")).isNull()
    }
}
