plugins {
    id("ticketing.application")
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-batch")
}
