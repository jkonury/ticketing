package io.github.ticketing

import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    fun containerProperties() = DynamicPropertyRegistrar { registry ->
        val namespace = SharedContainers.newNamespace()
        namespace.properties.forEach { (name, value) -> registry.add(name) { value } }
        registry.add("spring.kafka.bootstrap-servers", SharedContainers.kafka::getBootstrapServers)
        registry.add("ticketing.kafka.consumer.retry.initial-interval") { "300ms" }
        registry.add("ticketing.kafka.consumer.retry.max-interval") { "1s" }
    }
}

// Spring 빈의 수명과 분리해야 컨텍스트가 닫혀도 다른 컨텍스트가 쓰는 컨테이너가 종료되지 않는다.
internal object SharedContainers {
    private const val REDIS_DATABASES = 1024
    private val namespaces = AtomicInteger()

    private val postgres by lazy { PostgreSQLContainer("postgres:18.3-alpine").apply { start() } }
    private val redis by lazy {
        GenericContainer("redis:8.6.2-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--databases", REDIS_DATABASES.toString())
            .apply { start() }
    }

    val kafka by lazy { TestKafkaContainer().apply { start() } }

    fun newNamespace(): TestNamespace {
        val namespace = namespaces.incrementAndGet()
        check(namespace < REDIS_DATABASES) { "테스트 컨텍스트에 할당할 Redis DB가 부족하다" }
        val database = "test_$namespace"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            DSL.using(connection, SQLDialect.POSTGRES).createDatabase(database).execute()
        }
        return TestNamespace(
            mapOf(
                "spring.datasource.url" to "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database",
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "spring.datasource.hikari.minimum-idle" to "0",
                "spring.data.redis.host" to redis.host,
                "spring.data.redis.port" to redis.firstMappedPort.toString(),
                "spring.data.redis.database" to namespace.toString(),
                "ticketing.kafka.topic-prefix" to "test$namespace",
            ),
        )
    }
}

internal class TestNamespace(val properties: Map<String, String>)
