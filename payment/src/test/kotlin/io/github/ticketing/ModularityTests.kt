package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    @Test
    fun `모듈 경계와 의존 방향을 검증한다`() {
        ApplicationModules.of(PaymentApplication::class.java).verify()
    }
}
