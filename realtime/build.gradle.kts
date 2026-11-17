import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("ticketing.spring-conventions")
}

tasks.named<BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<BootJar>("bootJar") {
    manifest.attributes("Enable-Native-Access" to "ALL-UNNAMED")
}

// realtime은 PostgreSQL에 접근하지 않는다. JPA, JDBC, jOOQ 의존성을 추가하지 않는다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
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
        val violations = runtimeModules.get().filter { module ->
            val parts = module.split(':')
            forbidden.any { term -> parts[0].contains(term, ignoreCase = true) || parts[1].contains(term, ignoreCase = true) }
        }
        check(violations.isEmpty()) { "금지된 데이터베이스 의존성: ${violations.joinToString()}" }
    }
}

val verifyNoDatabaseDependencies = tasks.register<VerifyNoDatabaseDependencies>("verifyNoDatabaseDependencies") {
    runtimeModules.set(runtimeModulesProvider)
}
tasks.named("check") { dependsOn(verifyNoDatabaseDependencies) }
