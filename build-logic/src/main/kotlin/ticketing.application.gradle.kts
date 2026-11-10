plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val bootVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion("boot")
    .get()
    .requiredVersion
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${bootVersion}"))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
