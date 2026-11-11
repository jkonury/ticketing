rootProject.name = "ticketing"

pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("core", "core-batch", "realtime", "gateway", "fake-pg", "e2e")
