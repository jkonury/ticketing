package io.github.ticketing

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    private val modules get() = applicationModules

    companion object {
        private val applicationModules = ApplicationModules.of(TicketingApplication::class.java)
    }

    @Test
    fun `모듈 경계와 의존 방향을 검증한다`() {
        modules.verify()
    }

    @Test
    fun `설계한 8개 모듈이 모두 인식된다`() {
        val names = modules.map { it.identifier.toString() }.toSet()
        kotlin.test.assertEquals(setOf("member", "catalog", "booking", "payment", "settlement", "notification", "audit", "coupon"), names)
    }

    @Test
    fun `payment 모듈은 core에 구현 없이 contract의 결제 계약 이벤트 5종만 남는다`() {
        val types = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("io.github.ticketing.payment")
            .map { it.name }
            .toSet()

        kotlin.test.assertEquals(
            setOf("PaymentRequested", "RefundRequested", "PaymentSucceeded", "PaymentFailed", "PaymentRefunded")
                .map { "io.github.ticketing.payment.$it" }
                .toSet(),
            types,
        )
    }
}
