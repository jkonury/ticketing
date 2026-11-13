import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("ticketing.kotlin-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("ticketing.kotlin-quality")
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${libs.findVersion("spring-modulith").get()}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.findVersion("spring-cloud").get()}")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus 지표 + OpenTelemetry 브리지·OTLP(HTTP) trace 내보내기를 묶은 starter (Boot 4.1)
    implementation("org.springframework.boot:spring-boot-starter-micrometer-metrics")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    // JvmMetricsAutoConfiguration이 클래스패스에 있으면 VirtualThreadMetrics를 자동 등록한다
    implementation("io.micrometer:micrometer-java21")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-micrometer-metrics-test")
}
