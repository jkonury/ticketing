plugins {
    id("ticketing.spring-conventions")
}

// Dockerfile이 파일 이름으로 jar를 복사하므로 버전과 무관하게 이름을 고정한다
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("fake-pg.jar")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.awaitility:awaitility-kotlin")
}
