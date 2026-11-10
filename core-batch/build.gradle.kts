plugins {
    id("ticketing.jpa-conventions")
}

dependencies {
    // core의 도메인·어댑터 코드를 공유한다 (M2에서 Spring Batch Job 추가)
    implementation(project(":core"))
}

// 실행 가능한 jar는 core-batch 자신의 것이다
tasks.bootJar { mainClass = "io.github.ticketing.batch.CoreBatchApplicationKt" }
