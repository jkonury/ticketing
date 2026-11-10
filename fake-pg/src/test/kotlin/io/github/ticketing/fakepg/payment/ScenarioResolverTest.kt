package io.github.ticketing.fakepg.payment

import io.github.ticketing.fakepg.payment.config.FakePgProperties
import io.github.ticketing.fakepg.payment.service.Scenario
import io.github.ticketing.fakepg.payment.service.ScenarioResolver
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import java.util.random.RandomGenerator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioResolverTest {

    private class FixedRandom(private val value: Double) : RandomGenerator {
        override fun nextLong(): Long = 0
        override fun nextDouble(): Double = value
    }

    private fun resolver(random: Double, behavior: FakePgProperties.Behavior) =
        ScenarioResolver(FakePgProperties(FakePgProperties.Webhook("http://localhost", "s"), behavior), FixedRandom(random))

    @Test
    fun `요청에 시나리오가 있으면 확률과 무관하게 그것을 쓴다`() {
        val resolver = resolver(0.0, FakePgProperties.Behavior(failureRate = 1.0))

        assertEquals(Scenario.DELAYED, resolver.resolve(Scenario.DELAYED))
    }

    @Test
    fun `확률이 모두 0이면 SUCCESS이다`() {
        assertEquals(Scenario.SUCCESS, resolver(0.0, FakePgProperties.Behavior()).resolve(null))
    }

    @Test
    fun `난수가 확률 미만이면 해당 시나리오이고 FAIL, TIMEOUT, DUPLICATE_WEBHOOK 순으로 우선한다`() {
        assertEquals(Scenario.FAIL, resolver(0.1, FakePgProperties.Behavior(failureRate = 0.5, timeoutRate = 0.5)).resolve(null))
        assertEquals(Scenario.TIMEOUT, resolver(0.1, FakePgProperties.Behavior(timeoutRate = 0.5, duplicateRate = 0.5)).resolve(null))
        assertEquals(Scenario.DUPLICATE_WEBHOOK, resolver(0.1, FakePgProperties.Behavior(duplicateRate = 0.5)).resolve(null))
        assertEquals(Scenario.SUCCESS, resolver(0.9, FakePgProperties.Behavior(failureRate = 0.5)).resolve(null))
    }

    @Test
    fun `확률이 0과 1 범위를 벗어나면 검증에 실패한다`() {
        val validator = Validation.buildDefaultValidatorFactory().validator
        val webhook = FakePgProperties.Webhook("http://localhost", "s")

        val invalid = validator.validate(FakePgProperties(webhook, FakePgProperties.Behavior(failureRate = 1.5, timeoutRate = -0.1)))
        val valid = validator.validate(FakePgProperties(webhook, FakePgProperties.Behavior(failureRate = 1.0)))

        assertEquals(setOf("behavior.failureRate", "behavior.timeoutRate"), invalid.map { it.propertyPath.toString() }.toSet())
        assertTrue(valid.isEmpty())
    }
}
