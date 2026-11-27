package io.github.ticketing.settlement

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import org.junit.jupiter.api.Test

class HexagonalArchitectureTests {
    @Test
    fun `정산은 adapter에서 application으로 application에서 domain으로 의존한다`() {
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("adapter").definedBy("io.github.ticketing.settlement.adapter..")
            .layer("application").definedBy("io.github.ticketing.settlement.application..")
            .layer("domain").definedBy("io.github.ticketing.settlement.domain..")
            .whereLayer("adapter").mayNotBeAccessedByAnyLayer()
            .whereLayer("application").mayOnlyBeAccessedByLayers("adapter")
            .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "adapter")
            .check(classes)
    }

    @Test
    fun `정산 도메인은 Spring JPA jOOQ에 의존하지 않는다`() {
        noClasses().that().resideInAPackage("io.github.ticketing.settlement.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "org.jooq..",
                "ticketing.jooq..",
            )
            .check(classes)
    }

    private companion object {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("io.github.ticketing.settlement")
    }
}
