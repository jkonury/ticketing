plugins {
    id("ticketing.jpa-conventions")
}

dependencies {
    // payment의 도메인·어댑터 코드를 공유한다
    implementation(project(":payment"))
}

// 실행 가능한 jar는 payment-batch 자신의 것이다
tasks.bootJar { mainClass = "io.github.ticketing.paymentbatch.PaymentBatchApplicationKt" }
