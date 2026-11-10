plugins {
    id("ticketing.spring-conventions")
}

// realtime은 PostgreSQL에 접근하지 않는다. JPA, JDBC, jOOQ 의존성을 추가하지 않는다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}
