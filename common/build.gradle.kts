plugins {
    id("ticketing.library-conventions")
    `java-test-fixtures`
}

// 앱 간 공용 클래스. 기능별 compileOnly 의존은 해당 기능을 쓰는 앱이 제공한다.
dependencies {
    // DomainException이 HttpStatus를 공개 API로 노출한다
    api("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework:spring-tx")
    compileOnly("org.springframework.batch:spring-batch-infrastructure")
    compileOnly("org.springframework.kafka:spring-kafka")
    compileOnly("tools.jackson.core:jackson-databind")
    compileOnly("org.jooq:jooq")
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("org.slf4j:slf4j-api")

    testFixturesApi("org.testcontainers:testcontainers-kafka")
    testFixturesImplementation("tools.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.batch:spring-batch-infrastructure")
    testImplementation("org.springframework:spring-tx")
    testImplementation("org.springframework:spring-orm")
    testImplementation("org.springframework.kafka:spring-kafka")
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("org.jooq:jooq")
    testImplementation("io.micrometer:micrometer-core")
}
