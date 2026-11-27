package io.github.ticketing.e2e.support

import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

data class JobRun(val exitCode: Int, val status: String?, val exitStatus: String?, val log: File)

/** Batch Job을 실행하는 jar와, 그 Job의 결과·메타데이터가 있는 DB. */
enum class BatchTarget(private val jarProperty: String, private val userProperty: String, private val dbProperty: String) {
    /** core-batch: 코어 DB(`TICKETING_POSTGRES_*`). */
    CORE("e2e.coreBatchJar", "e2e.dbUser", "e2e.dbName"),

    /** payment-batch: 결제 DB(`payment_db`, 전용 계정). */
    PAYMENT("e2e.paymentBatchJar", "e2e.paymentDbUser", "e2e.paymentDbName"),

    SETTLEMENT("e2e.settlementBatchJar", "e2e.settlementDbUser", "e2e.settlementDbName"),
    ;

    val jar: String get() = property(jarProperty)
    val dbUser: String get() = property(userProperty)
    val dbName: String get() = property(dbProperty)

    private fun property(key: String) = checkNotNull(System.getProperty(key)) { "${key}가 필요합니다(scripts/e2e/run.sh로 실행)." }
}

/** Batch 앱 bootJar를 배포 형태(별도 프로세스)로 실행하고 batch 스키마의 실행 상태를 읽는다. */
object BatchSupport {
    private val projectDir = File(checkNotNull(System.getProperty("e2e.projectDir")) { "e2e.projectDir이 필요합니다(scripts/e2e/run.sh로 실행)." })
    private val logDir = File(projectDir, "build/e2e/logs").also { it.mkdirs() }

    /** 단일 값·행을 `|` 구분 문자열로 돌려준다. 행은 줄바꿈으로 나뉜다. 기본 대상은 코어 DB다. */
    fun psql(sql: String, target: BatchTarget = BatchTarget.CORE): List<String> {
        val process = ProcessBuilder(
            "docker", "compose", "exec", "-T", "postgres", "psql", "-U", target.dbUser, "-d", target.dbName,
            "-v", "ON_ERROR_STOP=1", "-qtA", "-F", "|", "-c", sql,
        ).directory(projectDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "psql 실패: $output" }
        return output.lines().filter { it.isNotBlank() }
    }

    /** 결제 DB(`payment_db`)를 조회한다. */
    fun paymentPsql(sql: String): List<String> = psql(sql, BatchTarget.PAYMENT)

    fun settlementPsql(sql: String): List<String> = psql(sql, BatchTarget.SETTLEMENT)

    /** 인자 없는 Job도 재실행되도록 식별 파라미터 `e2eRun`을 항상 붙인다. 메타데이터는 [target]의 DB에서 읽는다. */
    fun runJob(job: String, vararg parameters: String, target: BatchTarget = BatchTarget.CORE): JobRun {
        val log = File(logDir, "batch-$job-${System.currentTimeMillis()}.log")
        val process = ProcessBuilder(
            listOf("java", "-jar", target.jar, "--spring.batch.job.name=$job") + parameters + "e2eRun=${Instant.now().toEpochMilli()}",
        ).directory(projectDir).redirectErrorStream(true).redirectOutput(log).start()
        check(process.waitFor(180, TimeUnit.SECONDS)) { "$job 시간 초과. 로그: $log".also { process.destroyForcibly() } }
        val row = psql(
            "SELECT e.status, e.exit_code FROM batch.batch_job_execution e JOIN batch.batch_job_instance i USING (job_instance_id) " +
                "WHERE i.job_name = '$job' ORDER BY e.job_execution_id DESC LIMIT 1",
            target,
        ).firstOrNull()?.split("|")
        return JobRun(process.exitValue(), row?.get(0), row?.get(1), log)
    }
}
