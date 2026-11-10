package io.github.ticketing

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    private val modules = ApplicationModules.of(TicketingApplication::class.java)

    @Test
    fun `모듈 경계와 의존 방향을 검증한다`() {
        modules.verify()
    }

    @Test
    fun `설계한 6개 모듈이 모두 인식된다`() {
        val names = modules.map { it.identifier.toString() }.toSet()
        kotlin.test.assertEquals(setOf("member", "catalog", "booking", "payment", "settlement", "notification"), names)
    }
}
