plugins {
    id("ticketing.application")
}

dependencies {
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.cloud.get()}"))
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
}
