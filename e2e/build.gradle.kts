plugins {
    id("ticketing.kotlin-conventions")
    id("ticketing.kotlin-quality")
}

dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation("org.springframework:spring-webflux")
    testImplementation("org.springframework:spring-context")
    testImplementation("io.projectreactor.netty:reactor-netty-http")
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
}

tasks.test { enabled = false }

val gatewayUrl = providers.systemProperty("e2e.gatewayUrl").orElse("http://localhost:8000")
val realtimeBUrl = providers.systemProperty("e2e.realtimeBUrl").orElse("http://localhost:8082")
tasks.register<Test>("e2eTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("e2e.gatewayUrl", gatewayUrl.get())
    systemProperty("e2e.realtimeBUrl", realtimeBUrl.get())
}
