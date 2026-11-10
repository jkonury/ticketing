package io.github.ticketing

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import org.junit.jupiter.api.Test

class HexagonalArchitectureTests {

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("io.github.ticketing")

    private val hexagonalModules = listOf("booking", "payment", "settlement")

    @Test
    fun `헥사고날 모듈은 adapter에서 application으로 application에서 domain으로만 의존한다`() {
        hexagonalModules.forEach { module ->
            val base = "io.github.ticketing.$module"
            layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("adapter").definedBy("$base.adapter..")
                .layer("application").definedBy("$base.application..")
                .layer("domain").definedBy("$base.domain..")
                .whereLayer("adapter").mayNotBeAccessedByAnyLayer()
                .whereLayer("application").mayOnlyBeAccessedByLayers("adapter")
                .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "adapter")
                .check(classes)
        }
    }

    @Test
    fun `도메인은 Spring JPA jOOQ에 의존하지 않는다`() {
        hexagonalModules.forEach { module ->
            noClasses().that().resideInAPackage("io.github.ticketing.$module.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "org.jooq..",
                    "ticketing.jooq..",
                )
                .allowEmptyShould(true)
                .check(classes)
        }
    }
}
