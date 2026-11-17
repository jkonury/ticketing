plugins {
    id("ticketing.kotlin-conventions")
    id("ticketing.kotlin-quality")
}

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.springframework:spring-webflux")
    testImplementation("org.springframework:spring-context")
    testImplementation("io.projectreactor.netty:reactor-netty-http")
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
}

tasks.test { enabled = false }

val gatewayUrl = providers.systemProperty("e2e.gatewayUrl").orElse("http://localhost:18000")
val realtimeBUrl = providers.systemProperty("e2e.realtimeBUrl").orElse("http://localhost:18082")
tasks.register<Test>("e2eTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("e2e.gatewayUrl", gatewayUrl.get())
    systemProperty("e2e.realtimeBUrl", realtimeBUrl.get())
    providers.systemProperty("e2e.adminEmail").orNull?.let { systemProperty("e2e.adminEmail", it) }
    providers.systemProperty("e2e.adminPassword").orNull?.let { systemProperty("e2e.adminPassword", it) }
}
