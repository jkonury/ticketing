package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.infrastructure.item.ExecutionContext

class KeysetItemReaderTests {
    private fun reader(values: List<Long>): KeysetItemReader<Long> = KeysetItemReader("test", 2, { it }) { after, limit ->
        values.filter { after == null || it > after }.take(limit)
    }

    @Test
    fun `페이지 경계를 넘어 순서대로 읽고 끝나면 null을 반환한다`() {
        val reader = reader(listOf(1, 2, 3, 4, 5))
        reader.open(ExecutionContext())
        assertThat((1..6).map { reader.read() }).containsExactly(1L, 2L, 3L, 4L, 5L, null)
    }

    @Test
    fun `빈 결과는 null을 반환한다`() {
        val reader = reader(emptyList())
        reader.open(ExecutionContext())
        assertThat(reader.read()).isNull()
    }

    @Test
    fun `마지막 반환 키를 저장해 새 reader에서 이어 읽는다`() {
        val context = ExecutionContext()
        val first = reader(listOf(1, 2, 3, 4))
        first.open(context)
        assertThat(first.read()).isEqualTo(1L)
        first.update(context)
        first.close()
        val restarted = reader(listOf(1, 2, 3, 4))
        restarted.open(context)
        assertThat((1..4).map { restarted.read() }).containsExactly(2L, 3L, 4L, null)
    }
}
