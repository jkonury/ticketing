plugins {
    id("ticketing.spring-conventions")
}

dependencies {
    implementation(project(":settlement"))
}

tasks.bootJar { mainClass = "io.github.ticketing.settlementbatch.SettlementBatchApplicationKt" }
