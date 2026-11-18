// 앱 간에 공유하는 라이브러리 모듈용 컨벤션. 실행 jar(bootJar)와 앱 공통 의존성(actuator, OTel 등)을 끌어오지 않는다.
// 의존은 모듈 build.gradle.kts에서 필요한 만큼만 선언한다(consumer가 이미 가진 것은 compileOnly).
plugins {
    id("ticketing.kotlin-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    id("ticketing.kotlin-quality")
}

val libs = versionCatalogs.named("libs")

dependencyManagement {
    imports {
        val boot = libs.findLibrary("spring-boot-dependencies").get().get()
        mavenBom("${boot.module}:${boot.versionConstraint.requiredVersion}")
    }
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
