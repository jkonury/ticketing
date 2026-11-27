package io.github.ticketing.codegen

import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.SchemaMappingType
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File

// core가 소유한 모듈별 스키마. db/migration의 V1과 같이 유지하되 payment는 payment 앱 소유라 코드 생성 대상에서 뺀다.
// 이전 검증 뒤 별도 마이그레이션으로 core의 payment 스키마를 제거한다(ADR 0040).
private val SCHEMAS = listOf("member", "catalog", "booking", "audit", "coupon")

/**
 * 사용법: JooqCodegenKt <마이그레이션 디렉터리> <출력 디렉터리>
 *
 * 생성 패키지를 `io.github.ticketing` 밖에 두는 이유: Modulith는 루트 패키지의 직계 하위 패키지를 모두 모듈로 본다.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "사용법: <마이그레이션 디렉터리> <출력 디렉터리>" }
    val (migrationDir, outputDir) = args

    File(outputDir).deleteRecursively()

    PostgreSQLContainer("postgres:18.3-alpine").use { postgres ->
        postgres.start()

        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("filesystem:$migrationDir")
            .load()
            .migrate()

        val configuration = Configuration()
            .withJdbc(
                Jdbc()
                    .withDriver("org.postgresql.Driver")
                    .withUrl(postgres.jdbcUrl)
                    .withUser(postgres.username)
                    .withPassword(postgres.password),
            )
            .withGenerator(
                Generator()
                    .withName("org.jooq.codegen.KotlinGenerator")
                    .withDatabase(
                        Database()
                            .withName("org.jooq.meta.postgres.PostgresDatabase")
                            .withSchemata(SCHEMAS.map { SchemaMappingType().withInputSchema(it) }),
                    )
                    .withGenerate(Generate().withDeprecated(false).withRelations(true))
                    .withTarget(Target().withPackageName("ticketing.jooq").withDirectory(outputDir)),
            )

        GenerationTool.generate(configuration)
    }
}
