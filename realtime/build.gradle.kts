plugins {
    id("ticketing.spring-conventions")
    id("ticketing.no-database")
}

tasks.withType<Test> {
    val updateOpenApi = providers.environmentVariable("UPDATE_OPENAPI").map { it == "1" }.orElse(false)
    inputs.property("updateOpenApi", updateOpenApi)
    inputs.files(rootProject.file("docs/api/openapi/realtime.json"))
    outputs.upToDateWhen { !updateOpenApi.get() }
    outputs.doNotCacheIf("OpenAPI 스펙을 갱신할 때") { updateOpenApi.get() }
    systemProperty("spring.reactor.netty.shutdown-quiet-period", "0ms")
}

// realtime은 PostgreSQL에 접근하지 않는다. JPA, JDBC, jOOQ 의존성을 추가하지 않는다.
dependencies {
    implementation(libs.springdoc.webflux.api)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation(testFixtures(project(":common")))
}
