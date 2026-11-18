package io.github.ticketing

import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    fun containerProperties() = DynamicPropertyRegistrar { registry ->
        val namespace = SharedContainers.nextNamespace()
        val postgres = SharedContainers.postgres
        val redis = SharedContainers.redis
        val kafka = SharedContainers.kafka
        val database = "test_$namespace"

        // 컨텍스트마다 데이터를 격리해 재시작 시 미완료 이벤트가 다른 테스트에서 재발행되지 않게 한다.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            DSL.using(connection, SQLDialect.POSTGRES).createDatabase(database).execute()
        }

        registry.add("spring.datasource.url") { "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database" }
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.datasource.hikari.minimum-idle") { 0 }
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        registry.add("spring.data.redis.host", redis::getHost)
        registry.add("spring.data.redis.port", redis::getFirstMappedPort)
        registry.add("spring.data.redis.database") { namespace }
    }
}

// Spring 빈의 수명과 분리해야 컨텍스트가 닫혀도 다른 컨텍스트가 쓰는 컨테이너가 종료되지 않는다.
private object SharedContainers {
    private const val REDIS_DATABASES = 1024
    private val namespaces = AtomicInteger()

    val postgres = PostgreSQLContainer("postgres:18.3-alpine").apply { start() }
    val redis = GenericContainer("redis:8.6.2-alpine")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--databases", REDIS_DATABASES.toString())
        .apply { start() }

    val kafka = KafkaContainer("apache/kafka:4.3.2").apply { start() }

    fun nextNamespace(): Int = namespaces.incrementAndGet().also {
        check(it < REDIS_DATABASES) { "테스트 컨텍스트에 할당할 Redis DB가 부족하다" }
    }
}
