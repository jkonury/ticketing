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

/**
 * 사용법: JooqCodegenKt <마이그레이션 디렉터리> <출력 디렉터리>
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
                            .withSchemata(SchemaMappingType().withInputSchema("settlement")),
                    )
                    .withGenerate(Generate().withDeprecated(false).withRelations(true))
                    .withTarget(Target().withPackageName("ticketing.jooq.settlement").withDirectory(outputDir)),
            )

        GenerationTool.generate(configuration)
    }
}
