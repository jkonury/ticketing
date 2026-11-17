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
    group = "verification"
    description = "실행 중인 Gateway·realtime에 대해 E2E 테스트를 실행한다(scripts/e2e/run.sh 참고)."
    // 외부 서버 상태에 의존하므로 입력이 같아도 항상 실행한다.
    outputs.upToDateWhen { false }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("e2e.gatewayUrl", gatewayUrl.get())
    systemProperty("e2e.realtimeBUrl", realtimeBUrl.get())
    providers.systemProperty("e2e.adminEmail").orNull?.let { systemProperty("e2e.adminEmail", it) }
    providers.systemProperty("e2e.adminPassword").orNull?.let { systemProperty("e2e.adminPassword", it) }
}
