rootProject.name = "ticketing"

pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include("common", "contract", "core", "core-batch", "payment", "payment-batch", "realtime", "gateway", "fake-pg", "e2e")
include("settlement", "settlement-batch")
include("loadtest-gatling")
project(":loadtest-gatling").projectDir = file("loadtest/gatling")
