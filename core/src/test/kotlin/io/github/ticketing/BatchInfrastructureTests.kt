package io.github.ticketing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.repository.JobRepository
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class BatchInfrastructureTests(private val repository: JobRepository, private val jdbcTemplate: JdbcTemplate) :
    IntegrationTestSupport() {
    @Test
    fun `배치 저장소는 batch 스키마에 작업 인스턴스를 저장한다`() {
        val name = "test-${UUID.randomUUID()}"
        val instance = repository.createJobInstance(name, JobParameters())
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM batch.batch_job_instance WHERE job_instance_id = ? AND job_name = ?",
            Long::class.java,
            instance.instanceId,
            name,
        )
        assertThat(count).isEqualTo(1L)
        assertThat(repository.getJobInstance(name, JobParameters())?.instanceId).isEqualTo(instance.instanceId)
    }
}
