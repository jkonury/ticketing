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
include("loadtest-gatling")
project(":loadtest-gatling").projectDir = file("loadtest/gatling")
