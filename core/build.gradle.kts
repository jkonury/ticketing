buildscript {
    dependencies {
        classpath(libs.flyway.postgres)
        classpath(libs.postgres)
    }
}

plugins {
    id("ticketing.application")
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.jooq)
    alias(libs.plugins.flyway)
}

dependencies {
    implementation(platform("org.springframework.modulith:spring-modulith-bom:${libs.versions.modulith.get()}"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-jpa")
    runtimeOnly("org.postgresql:postgresql")

    jooqCodegen(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.boot.get()}"))
    jooqCodegen("org.postgresql:postgresql")

    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:${libs.versions.archunit.get()}")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

flyway {
    url = System.getenv("TICKETING_DB_URL") ?: "jdbc:postgresql://localhost:5432/ticketing"
    user = System.getenv("TICKETING_DB_USER") ?: "ticketing"
    password = System.getenv("POSTGRES_PASSWORD")
    locations = arrayOf("filesystem:src/main/resources/db/migration")
}

jooq {
    configuration {
        jdbc {
            driver = "org.postgresql.Driver"
            url = System.getenv("TICKETING_DB_URL") ?: "jdbc:postgresql://localhost:5432/ticketing"
            user = System.getenv("TICKETING_DB_USER") ?: "ticketing"
            password = System.getenv("POSTGRES_PASSWORD")
        }

        generator {
            database {
                name = "org.jooq.meta.postgres.PostgresDatabase"
                includes = ".*"
                schemata {
                    listOf("member", "catalog", "booking", "payment", "settlement").forEach { schema ->
                        schema {
                            inputSchema = schema
                        }
                    }
                }
            }

            target {
                packageName = "io.github.ticketing.generated"
                directory = "build/generated-src/jooq"
            }
        }
    }
}

tasks.named("jooqCodegen") {
    dependsOn("flywayMigrate")
}

sourceSets.main {
    java.srcDir("build/generated-src/jooq")
}
