plugins {
    kotlin("jvm")
}

val libs = versionCatalogs.named("libs")

group = "io.github.ticketing"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(libs.findVersion("java").get().requiredVersion.toInt())
    compilerOptions {
        // Java 어노테이션의 null 정보를 Kotlin 타입에 엄격하게 반영한다
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
