// PostgreSQL에 접근하지 않는 모듈(realtime, gateway)의 런타임 클래스패스에 DB 관련 의존성이 없는지 검증한다.
plugins {
    java
}

val runtimeModulesProvider = configurations.runtimeClasspath.get().incoming.resolutionResult.rootComponent.map { root ->
    val visited = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
    fun collect(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
        if (visited.add(component)) {
            component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { collect(it.selected) }
        }
    }
    collect(root)
    visited.mapNotNull { it.moduleVersion?.let { module -> "${module.group}:${module.name}:${module.version}" } }.sorted()
}

abstract class VerifyNoDatabaseDependencies : DefaultTask() {
    @get:Input
    abstract val runtimeModules: ListProperty<String>

    @TaskAction
    fun verify() {
        val forbidden = listOf("jdbc", "jpa", "hibernate", "jooq", "r2dbc", "postgresql", "flyway", "liquibase")
        // hibernate-validator는 Bean Validation 구현체라 DB와 무관하다.
        val allowed = listOf("org.hibernate.validator:")
        val violations = runtimeModules.get().filter { module ->
            val parts = module.split(':')
            allowed.none { module.startsWith(it) } &&
                forbidden.any { term -> parts[0].contains(term, ignoreCase = true) || parts[1].contains(term, ignoreCase = true) }
        }
        check(violations.isEmpty()) { "금지된 데이터베이스 의존성: ${violations.joinToString()}" }
    }
}

val verifyNoDatabaseDependencies = tasks.register<VerifyNoDatabaseDependencies>("verifyNoDatabaseDependencies") {
    group = "verification"
    description = "런타임 클래스패스에 DB 관련 의존성이 없는지 검증한다."
    runtimeModules.set(runtimeModulesProvider)
}
tasks.named("check") { dependsOn(verifyNoDatabaseDependencies) }
