// k6 시나리오(loadtest/scenarios)와 같은 흐름을 Gatling Kotlin DSL로 옮긴 모듈.
// 서비스 모듈(core, gateway 등)에 의존하지 않고 Gateway(18000)를 HTTP로만 호출한다.
plugins {
    id("ticketing.kotlin-conventions")
    id("ticketing.kotlin-quality")
    alias(libs.plugins.gatling)
}

// 환경 변수(BASE_URL, TICKETING_JWT_SECRET, MEMBERS_FILE, SCHEDULE_FILE, VUS 등)는 gatlingRun의 forked JVM이 그대로 상속한다.

// Gradle 없이 `java -cp 'lib/*' io.gatling.app.Gatling`로 직접 실행할 수 있게 클래스와 의존성을 한 디렉터리에 모은다.
// Gradle 데몬·래퍼를 거치지 않아 프로세스 PID가 곧 Gatling이므로 taskset·메모리 샘플링에 그대로 쓸 수 있다.
val gatlingClassesJar =
    tasks.register<Jar>("gatlingClassesJar") {
        archiveBaseName.set("ticketing-loadtest-gatling")
        from(sourceSets["gatling"].output)
    }

val gatlingDist =
    tasks.register<Sync>("gatlingDist") {
        group = "gatling"
        description = "Gradle 없이 실행할 수 있는 Gatling 배포 디렉터리(build/gatling-dist)를 만든다."
        into(layout.buildDirectory.dir("gatling-dist"))
        into("lib") {
            // 클래스 디렉터리는 jar 하나로 묶어 넣는다. `lib/*` 와일드카드는 jar만 읽는다
            from(sourceSets["gatling"].runtimeClasspath.filter { it.isFile })
            from(gatlingClassesJar)
        }
    }
