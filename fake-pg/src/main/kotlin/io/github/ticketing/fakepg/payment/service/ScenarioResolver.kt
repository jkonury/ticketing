package io.github.ticketing.fakepg.payment.service

import io.github.ticketing.fakepg.payment.config.FakePgProperties
import org.springframework.stereotype.Component
import java.util.random.RandomGenerator

/** 요청에 시나리오가 없으면 설정 확률로 정한다. 우선순위는 FAIL > TIMEOUT > DUPLICATE_WEBHOOK. */
@Component
class ScenarioResolver(
    private val properties: FakePgProperties,
    private val random: RandomGenerator,
) {

    fun resolve(requested: Scenario?): Scenario {
        if (requested != null) return requested
        val behavior = properties.behavior
        return when {
            random.nextDouble() < behavior.failureRate -> Scenario.FAIL
            random.nextDouble() < behavior.timeoutRate -> Scenario.TIMEOUT
            random.nextDouble() < behavior.duplicateRate -> Scenario.DUPLICATE_WEBHOOK
            else -> Scenario.SUCCESS
        }
    }
}
