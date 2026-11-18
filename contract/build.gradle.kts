plugins {
    id("ticketing.library-conventions")
    `java-test-fixtures`
}

// 프로세스 경계를 넘는 계약 이벤트. 운영 코드는 Kotlin·JDK 타입만 쓴다(Spring·Jackson 의존 없음).
// 샘플 이벤트와 골든 JSON은 testFixtures로 공유해 core가 실제 직렬화기로도 같은 형식을 검증한다.
// 직렬화 테스트에만 Jackson을 쓴다.
dependencies {
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("tools.jackson.module:jackson-module-kotlin")
}
