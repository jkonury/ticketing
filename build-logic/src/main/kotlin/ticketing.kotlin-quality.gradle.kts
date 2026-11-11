// Kotlin 서비스에 공통 품질 검사를 적용한다. 포맷은 ktlint, 정적 분석은 detekt가 맡는다.
plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
}

val libs = versionCatalogs.named("libs")

ktlint {
    version.set(libs.findVersion("ktlint").get().requiredVersion)
    filter {
        exclude("**/ticketing/jooq/**")
        exclude { element -> element.file.invariantSeparatorsPath.contains("/build/generated-sources/") }
    }
}

detekt {
    toolVersion.set(libs.findVersion("detekt").get().requiredVersion)
    buildUponDefaultConfig.set(true)
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    baseline.set(layout.projectDirectory.file("detekt-baseline.xml"))
    basePath.set(rootProject.layout.projectDirectory)
}
