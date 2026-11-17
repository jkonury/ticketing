import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("ticketing.kotlin-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("ticketing.kotlin-quality")
}

val libs = versionCatalogs.named("libs")

// 카탈로그 library(group:name 과 version)를 BOM 좌표 문자열로 바꾼다.
fun bomNotation(alias: String): String {
    val dependency = libs.findLibrary(alias).get().get()
    return "${dependency.module}:${dependency.versionConstraint.requiredVersion}"
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
    // Netty(Lettuce 등)의 네이티브 접근 경고를 막는다.
    manifest.attributes("Enable-Native-Access" to "ALL-UNNAMED")
}

tasks.named<BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencyManagement {
    imports {
        mavenBom(bomNotation("spring-modulith-bom"))
        mavenBom(bomNotation("spring-cloud-dependencies"))
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

    mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks {
    // modulithDocs 같은 별도 Test task에도 같은 JVM 설정을 적용한다.
    withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        jvmArgumentProviders.add(
            objects.newInstance<MockitoAgentProvider>().apply {
                agentJar.from(mockitoAgent)
            },
        )
    }
}
