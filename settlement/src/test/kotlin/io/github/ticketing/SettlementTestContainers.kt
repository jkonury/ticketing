package io.github.ticketing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

@TestConfiguration(proxyBeanMethods = false)
class SettlementTestContainers {
    @Bean
    fun containerProperties() = DynamicPropertyRegistrar { registry ->
        SettlementTestDatabases.newNamespace().forEach { (name, value) -> registry.add(name) { value } }
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
    }

    companion object {
        private val kafka by lazy { TestKafkaContainer().apply { start() } }
    }
}

internal object SettlementTestDatabases {
    const val CORE_USER = "core_app"
    const val TEST_PASSWORD = "test-password"
    const val CORE_DATABASE = "core_test"
    private val namespaces = AtomicInteger()
    private val postgres by lazy {
        PostgreSQLContainer("postgres:18.3-alpine").withUsername("local_admin").apply {
            start()
            DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE ROLE $CORE_USER LOGIN PASSWORD '$TEST_PASSWORD'")
                    statement.execute("CREATE DATABASE $CORE_DATABASE OWNER $CORE_USER")
                }
            }
            val provisionSql = Path.of("../deploy/postgres/init/20-settlement-db.sql").toAbsolutePath().normalize()
            copyFileToContainer(MountableFile.forHostPath(provisionSql), "/tmp/ensure-settlement.sql")
        }
    }

    fun jdbcUrl(database: String) = "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$database"

    fun newNamespace(): Map<String, String> {
        val namespace = namespaces.incrementAndGet()
        val database = "settlement_$namespace"
        val user = "settlement_user_$namespace"
        val result = postgres.execInContainer(
            "env",
            "TICKETING_POSTGRES_DB=$CORE_DATABASE",
            "TICKETING_PAYMENT_POSTGRES_DB=payment_db",
            "TICKETING_PAYMENT_POSTGRES_USER=payment",
            "TICKETING_SETTLEMENT_POSTGRES_DB=$database",
            "TICKETING_SETTLEMENT_POSTGRES_USER=$user",
            "TICKETING_SETTLEMENT_POSTGRES_PASSWORD=$TEST_PASSWORD",
            "psql",
            "-X",
            "--set=ON_ERROR_STOP=1",
            "--username=${postgres.username}",
            "--dbname=$CORE_DATABASE",
            "--file=/tmp/ensure-settlement.sql",
        )
        check(result.exitCode == 0) { result.stderr }
        return mapOf(
            "spring.datasource.url" to jdbcUrl(database),
            "spring.datasource.username" to user,
            "spring.datasource.password" to TEST_PASSWORD,
            "spring.datasource.hikari.minimum-idle" to "0",
            "ticketing.kafka.topic-prefix" to "settlement-test$namespace",
        )
    }
}
