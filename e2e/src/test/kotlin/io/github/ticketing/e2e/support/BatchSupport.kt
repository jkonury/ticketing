package io.github.ticketing.e2e.support

import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

data class JobRun(val exitCode: Int, val status: String?, val exitStatus: String?, val log: File)

/** core-batch bootJar를 배포 형태(별도 프로세스)로 실행하고 batch 스키마의 실행 상태를 읽는다. */
object BatchSupport {
    private val projectDir = File(checkNotNull(System.getProperty("e2e.projectDir")) { "e2e.projectDir이 필요합니다(scripts/e2e/run.sh로 실행)." })
    private val jar = checkNotNull(System.getProperty("e2e.coreBatchJar")) { "e2e.coreBatchJar가 필요합니다." }
    private val dbUser = checkNotNull(System.getProperty("e2e.dbUser")) { "e2e.dbUser가 필요합니다." }
    private val dbName = checkNotNull(System.getProperty("e2e.dbName")) { "e2e.dbName이 필요합니다." }
    private val logDir = File(projectDir, "build/e2e/logs").also { it.mkdirs() }

    /** 단일 값·행을 `|` 구분 문자열로 돌려준다. 행은 줄바꿈으로 나뉜다. */
    fun psql(sql: String): List<String> {
        val process = ProcessBuilder(
            "docker", "compose", "exec", "-T", "postgres", "psql", "-U", dbUser, "-d", dbName,
            "-v", "ON_ERROR_STOP=1", "-qtA", "-F", "|", "-c", sql,
        ).directory(projectDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "psql 실패: $output" }
        return output.lines().filter { it.isNotBlank() }
    }

    /** 인자 없는 Job도 재실행되도록 식별 파라미터 `e2eRun`을 항상 붙인다. */
    fun runJob(job: String, vararg parameters: String): JobRun {
        val log = File(logDir, "batch-$job-${System.currentTimeMillis()}.log")
        val process = ProcessBuilder(
            listOf("java", "-jar", jar, "--spring.batch.job.name=$job") + parameters + "e2eRun=${Instant.now().toEpochMilli()}",
        ).directory(projectDir).redirectErrorStream(true).redirectOutput(log).start()
        check(process.waitFor(180, TimeUnit.SECONDS)) { "$job 시간 초과. 로그: $log".also { process.destroyForcibly() } }
        val row = psql(
            "SELECT e.status, e.exit_code FROM batch.batch_job_execution e JOIN batch.batch_job_instance i USING (job_instance_id) " +
                "WHERE i.job_name = '$job' ORDER BY e.job_execution_id DESC LIMIT 1",
        ).firstOrNull()?.split("|")
        return JobRun(process.exitValue(), row?.get(0), row?.get(1), log)
    }
}
