package io.github.ticketing

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // static이 아닌 빈이라 컨테이너가 컨텍스트 캐시와 함께 재사용된다
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:17")

    @Bean
    @ServiceConnection(name = "redis")
    fun redis(): GenericContainer<*> = GenericContainer("redis:7").withExposedPorts(6379)
}
