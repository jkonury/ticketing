package io.github.ticketing

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/** Testcontainers(PostgreSQL, Redis) 위에서 전체 컨텍스트를 띄우는 통합 테스트 베이스. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
abstract class IntegrationTestSupport
