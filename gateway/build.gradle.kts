plugins {
    id("ticketing.spring-conventions")
    id("ticketing.no-database")
}

tasks.withType<Test> {
    systemProperty("spring.reactor.netty.shutdown-quiet-period", "0ms")
}

// gateway는 PostgreSQL에 접근하지 않는다. JPA, JDBC, jOOQ 의존성을 추가하지 않는다.
dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}
