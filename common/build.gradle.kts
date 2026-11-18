plugins {
    id("ticketing.library-conventions")
}

// 앱 간 공용 클래스. 웹·Batch 의존은 compileOnly라 해당 기능을 쓰는 앱(core, core-batch, payment 등)이 이미 가진 것을 쓴다.
dependencies {
    // DomainException이 HttpStatus를 공개 API로 노출한다
    api("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework:spring-tx")
    compileOnly("org.springframework.batch:spring-batch-infrastructure")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.batch:spring-batch-infrastructure")
    testImplementation("org.springframework:spring-tx")
    testImplementation("org.springframework:spring-orm")
}
