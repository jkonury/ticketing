package io.github.ticketing

import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

@ActiveProfiles("batch")
@TestPropertySource(properties = ["spring.batch.job.enabled=false"])
abstract class BatchIntegrationTestSupport : IntegrationTestSupport()
