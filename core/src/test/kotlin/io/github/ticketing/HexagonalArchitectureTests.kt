package io.github.ticketing

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Query

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

    @Test
    fun `main 코드는 문자열 SQL을 쓰지 않는다`() {
        noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
            .orShould().callMethod(EntityManager::class.java, "createNativeQuery", String::class.java)
            .orShould().callMethod(EntityManager::class.java, "createNativeQuery", String::class.java, Class::class.java)
            .orShould().callMethod(EntityManager::class.java, "createNativeQuery", String::class.java, String::class.java)
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `Query 애너테이션에 nativeQuery를 쓰지 않는다`() {
        methods().should(object : ArchCondition<JavaMethod>("not use @Query(nativeQuery = true)") {
            override fun check(method: JavaMethod, events: ConditionEvents) {
                val query = method.tryGetAnnotationOfType(Query::class.java).orElse(null) ?: return
                if (query.nativeQuery) events.add(SimpleConditionEvent.violated(method, "${method.fullName}에 nativeQuery=true"))
            }
        }).allowEmptyShould(true).check(classes)
    }
}
