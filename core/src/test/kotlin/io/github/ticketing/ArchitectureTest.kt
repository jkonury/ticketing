package io.github.ticketing

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ArchitectureTest {
    @Test
    fun `모듈 경계를 지킨다`() {
        ApplicationModules.of(TicketingApplication::class.java).verify()
    }

    @Test
    fun `헥사고날 의존 방향을 지킨다`() {
        val classes = ClassFileImporter().importPackages("io.github.ticketing")
        for (module in listOf("booking", "payment", "settlement")) {
            val root = "io.github.ticketing.$module"
            noClasses().that().resideInAPackage("$root.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.jooq..", "$root.application..", "$root.adapter..")
                .allowEmptyShould(true).check(classes)
            noClasses().that().resideInAPackage("$root.application..")
                .should().dependOnClassesThat().resideInAPackage("$root.adapter..")
                .allowEmptyShould(true).check(classes)
        }
    }
}
