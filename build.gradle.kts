// 루트·build-logic의 Kotlin 빌드 스크립트도 검사한다.
plugins {
    base
    id("ticketing.kotlin-quality")
}

ktlint {
    kotlinScriptAdditionalPaths {
        include(fileTree("build-logic") { include("*.gradle.kts", "src/main/kotlin/*.gradle.kts") })
    }
}
