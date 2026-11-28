package io.github.ticketing

import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

object BatchJobMetricsFixture {
    fun insertExecution(
        dataSource: DataSource,
        jobName: String,
        status: String,
        startedAt: LocalDateTime,
        endedAt: LocalDateTime? = startedAt.plusSeconds(1),
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                WITH instance AS (
                    INSERT INTO batch.batch_job_instance (job_instance_id, job_name, job_key)
                    VALUES (nextval('batch.batch_job_instance_seq'), ?, ?)
                    RETURNING job_instance_id
                )
                INSERT INTO batch.batch_job_execution (job_execution_id, job_instance_id, create_time, start_time, end_time, status)
                SELECT nextval('batch.batch_job_execution_seq'), job_instance_id, ?, ?, ?, ? FROM instance
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, jobName)
                statement.setString(2, UUID.randomUUID().toString().replace("-", ""))
                statement.setObject(3, startedAt)
                statement.setObject(4, startedAt)
                statement.setObject(5, endedAt)
                statement.setString(6, status)
                statement.executeUpdate()
            }
        }
    }
}
