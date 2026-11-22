plugins {
    id("ticketing.jpa-conventions")
}

// jOOQ 코드 생성기. 애플리케이션 클래스패스와 분리해 Flyway·Testcontainers 같은 도구 의존성이 새어 들어가지 않게 한다.
val jooqGenerator = sourceSets.create("jooqGenerator")

dependencies {
    implementation(project(":common"))
    implementation(project(":contract"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")

    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.archunit.junit5)
    testImplementation(testFixtures(project(":contract")))

    "jooqGeneratorImplementation"(kotlin("stdlib"))
    "jooqGeneratorImplementation"("org.jooq:jooq-codegen")
    "jooqGeneratorImplementation"("org.flywaydb:flyway-core")
    "jooqGeneratorImplementation"("org.flywaydb:flyway-database-postgresql")
    "jooqGeneratorImplementation"("org.testcontainers:testcontainers-postgresql")
    "jooqGeneratorRuntimeOnly"("org.postgresql:postgresql")
    "jooqGeneratorRuntimeOnly"("org.slf4j:slf4j-simple")
}

// Flyway 마이그레이션이 만든 스키마를 기준으로 jOOQ 코드를 생성한다. 생성 코드는 build/ 아래에만 두고 커밋하지 않는다.
// 임시 PostgreSQL 컨테이너를 띄우므로 Docker가 필요하다.
val jooqOutputDir = layout.buildDirectory.dir("generated-sources/jooq")
val migrationDir = layout.projectDirectory.dir("src/main/resources/db/migration")

// 절대 경로를 인자로 넘기되 입력·출력은 상대 경로 기준으로 선언해, checkout 위치가 달라도(워크트리) 빌드 캐시를 공유한다.
abstract class JooqCodegenArgs : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val migrations: DirectoryProperty

    @get:OutputDirectory
    abstract val generated: DirectoryProperty

    override fun asArguments(): Iterable<String> = listOf(migrations.get().asFile.absolutePath, generated.get().asFile.absolutePath)
}

val generateJooq = tasks.register<JavaExec>("generateJooq") {
    group = "build"
    description = "Flyway 마이그레이션을 임시 PostgreSQL에 적용하고 jOOQ 코드를 생성한다."
    classpath = jooqGenerator.runtimeClasspath
    mainClass = "io.github.ticketing.codegen.JooqCodegenKt"
    argumentProviders.add(
        objects.newInstance<JooqCodegenArgs>().apply {
            migrations.set(migrationDir)
            generated.set(jooqOutputDir)
        },
    )
    // 같은 마이그레이션·생성기 클래스패스면 결과가 같으므로 캐시한다.
    outputs.cacheIf { true }
}

sourceSets.main {
    kotlin.srcDir(jooqOutputDir)
}

tasks.compileKotlin {
    dependsOn(generateJooq)
}

// ktlint 소스 집합에 생성 코드 디렉터리가 포함되므로 생성이 끝난 뒤에 검사한다(제외 필터와 별개로 Gradle이 출력 의존을 검증한다)
tasks.matching { it.name.startsWith("runKtlint") || it.name.startsWith("ktlint") }.configureEach {
    mustRunAfter(generateJooq)
}
