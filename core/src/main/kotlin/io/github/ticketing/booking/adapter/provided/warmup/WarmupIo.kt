package io.github.ticketing.booking.adapter.provided.warmup

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

internal class WarmupIoCounts {
    val dbStatements = AtomicInteger()
    val redisCommands = AtomicInteger()
}

internal object WarmupIo {
    private val current = ThreadLocal<WarmupIoCounts>()

    fun redisCommand() {
        current.get()?.let { it.redisCommands.incrementAndGet() }
    }

    fun <T> measure(counts: WarmupIoCounts, action: () -> T): T {
        check(current.get() == null)
        current.set(counts)
        return try {
            action()
        } finally {
            current.remove()
        }
    }

    fun statement() {
        current.get()?.let { it.dbStatements.incrementAndGet() }
    }
}

@Component
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class WarmupDataSourcePostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
        if (bean is DataSource) CountingDataSource(bean) else bean

    private class CountingDataSource(private val delegate: DataSource) : DataSource by delegate {
        override fun getConnection(): Connection = connection(delegate.connection)

        override fun getConnection(username: String, password: String): Connection = connection(delegate.getConnection(username, password))

        private fun connection(connection: Connection): Connection = wrap(Connection::class.java, connection) { result ->
            if (result is Statement) {
                val contract = when (result) {
                    is java.sql.CallableStatement -> java.sql.CallableStatement::class.java
                    is java.sql.PreparedStatement -> java.sql.PreparedStatement::class.java
                    else -> Statement::class.java
                }
                statement(contract, result)
            } else {
                result
            }
        }

        private fun statement(contract: Class<*>, statement: Statement): Any =
            Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, arguments ->
                if (method.name in EXECUTIONS) WarmupIo.statement()
                invoke(statement, method, arguments)
            }
    }

    private companion object {
        val EXECUTIONS = setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch")

        fun <T> wrap(contract: Class<T>, target: T, transform: (Any?) -> Any?): T = contract.cast(
            Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, arguments ->
                transform(invoke(target, method, arguments))
            },
        )

        fun invoke(target: Any?, method: java.lang.reflect.Method, arguments: Array<out Any?>?): Any? = try {
            method.invoke(target, *(arguments ?: emptyArray()))
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
    }
}
