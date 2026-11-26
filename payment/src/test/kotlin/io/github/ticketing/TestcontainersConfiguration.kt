package io.github.ticketing

import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    fun containerProperties() = DynamicPropertyRegistrar { registry ->
        SharedContainers.newNamespace().forEach { (name, value) -> registry.add(name) { value } }
        registry.add("spring.kafka.bootstrap-servers", SharedContainers.kafka::getBootstrapServers)
        registry.add("ticketing.kafka.consumer.retry.initial-interval") { "300ms" }
        registry.add("ticketing.kafka.consumer.retry.max-interval") { "1s" }
    }
}

// 컨텍스트가 닫혀도 같은 JVM의 다른 컨텍스트에서 공유 컨테이너를 쓴다.
internal object SharedContainers {
    private val namespaces = AtomicInteger()
    private val postgres by lazy { PostgreSQLContainer("postgres:18.3-alpine").withUsername("payment").apply { start() } }
    val kafka by lazy { TestKafkaContainer().apply { start() } }

    fun newNamespace(): Map<String, String> {
        val namespace = namespaces.incrementAndGet()
        val database = "test_$namespace"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            DSL.using(connection, SQLDialect.POSTGRES).createDatabase(database).execute()
        }
        return mapOf(
            "spring.datasource.url" to "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database",
            "spring.datasource.username" to postgres.username,
            "spring.datasource.password" to postgres.password,
            "spring.datasource.hikari.minimum-idle" to "0",
            "ticketing.kafka.topic-prefix" to "test$namespace",
        )
    }
}
