plugins {
    id("ticketing.jpa-conventions")
}

val libs = the<VersionCatalogsExtension>().named("libs")

// jOOQ 코드 생성기. 애플리케이션 클래스패스와 분리해 Flyway·Testcontainers 같은 도구 의존성이 새어 들어가지 않게 한다.
val jooqGenerator: SourceSet by sourceSets.creating

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation(libs.findLibrary("archunit-junit5").get())

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

val generateJooq by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Flyway 마이그레이션을 임시 PostgreSQL에 적용하고 jOOQ 코드를 생성한다."
    classpath = jooqGenerator.runtimeClasspath
    mainClass = "io.github.ticketing.codegen.JooqCodegenKt"
    inputs.dir(migrationDir).withPropertyName("migrations")
    outputs.dir(jooqOutputDir).withPropertyName("generated")
    args(migrationDir.asFile.absolutePath, jooqOutputDir.get().asFile.absolutePath)
}

sourceSets.main {
    kotlin.srcDir(jooqOutputDir)
}

tasks.compileKotlin {
    dependsOn(generateJooq)
}
