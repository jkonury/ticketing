package io.github.ticketing

import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader

class KeysetItemReader<T : Any>(
    name: String,
    private val pageSize: Int,
    private val keyOf: (T) -> Long,
    private val fetchPage: (afterKey: Long?, limit: Int) -> List<T>,
) : ItemStreamReader<T> {
    private val contextKey = "$name.lastKey"
    private var lastKey: Long? = null
    private var page: Iterator<T> = emptyList<T>().iterator()

    init {
        require(name.isNotBlank())
        require(pageSize > 0)
    }

    override fun read(): T? {
        if (!page.hasNext()) page = fetchPage(lastKey, pageSize).iterator()
        if (!page.hasNext()) return null
        return page.next().also { lastKey = keyOf(it) }
    }

    override fun open(executionContext: ExecutionContext) {
        lastKey = if (executionContext.containsKey(contextKey)) executionContext.getLong(contextKey) else null
        page = emptyList<T>().iterator()
    }

    override fun update(executionContext: ExecutionContext) {
        lastKey?.let { executionContext.putLong(contextKey, it) }
    }

    override fun close() {
        page = emptyList<T>().iterator()
    }
}
