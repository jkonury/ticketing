plugins {
    id("ticketing.kotlin-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("ticketing.kotlin-quality")
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
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

configurations.configureEach {
    if (name.contains("test", ignoreCase = true)) {
        exclude(group = "org.mockito")
        exclude(group = "io.mockk")
    }
}

val integrationTest = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    target.compilations.getByName("integrationTest").associateWith(target.compilations.getByName("main"))
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "통합 및 구조 검증을 실행한다."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    exclude("**/DocumentationTests.class")
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTestTask)
}

val checkUnitBoundaries by tasks.registering(Exec::class) {
    group = "verification"
    description = "단위 테스트의 Spring 기동, 컨테이너, mock 사용을 검사한다."
    commandLine("bash", rootProject.file("scripts/agent/test/check-unit-boundaries.sh"), project.file("src/test"))
}

tasks.check {
    dependsOn(checkUnitBoundaries)
}

tasks.test {
    dependsOn(checkUnitBoundaries)
}
