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

val mockitoAgent = configurations.create("mockitoAgent")

abstract class MockitoAgentProvider : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val agentJar: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf("-javaagent:${agentJar.asPath}")
}

dependencies {
    // 빌드 호스트와 실행 호스트의 CPU가 달라도 macOS 시스템 DNS를 사용할 수 있게 한다.
    runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-aarch_64")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos::osx-x86_64")

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

    testImplementation("org.mockito:mockito-core")
    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks {
    test {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        jvmArgumentProviders.add(
            objects.newInstance<MockitoAgentProvider>().apply {
                agentJar.from(mockitoAgent)
            },
        )
    }
}
