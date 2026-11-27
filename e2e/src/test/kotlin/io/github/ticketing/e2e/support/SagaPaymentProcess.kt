package io.github.ticketing.e2e.support

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class SagaPaymentProcess {
    private val directory = File(sagaProperty("projectDir"))
    private val pidFile = File(runDirectory(), "payment.pid")
    private val jar = sagaProperty("paymentJar")
    private val port = sagaProperty("paymentPort")
    private val bootstrap = sagaProperty("kafkaBootstrap")
    private val launch = readLaunch(requireNotNull(runningProcess()) { "실행기가 소유한 payment 프로세스가 없습니다." })

    fun stop() {
        val process = requireNotNull(runningProcess()) { "종료할 payment 프로세스가 없습니다." }
        val exited = process.onExit()
        process.destroy()
        try {
            exited.get(45, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            process.destroyForcibly()
            exited.get(10, TimeUnit.SECONDS)
        }
    }

    suspend fun restore() {
        if (runningProcess() != null) return
        val log = File.createTempFile("payment-restart-", ".log", runDirectory())
        val process = ProcessBuilder(launch.command)
            .directory(directory)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply {
                environment().clear()
                environment().putAll(launch.environment)
            }.start()
        try {
            pidFile.writeAtomically("${process.pid()} ${startTicks(process.pid())}\n")
            awaitReadiness(process)
        } catch (failure: Exception) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            throw IllegalStateException("payment 재기동 실패. 로그: $log", failure)
        }
    }

    private fun runningProcess(): ProcessHandle? {
        val (pid, started) = pidFile.readText().trim().split(' ')
        val process = ProcessHandle.of(pid.toLong()).orElse(null) ?: return null
        return process.takeIf { it.isAlive && runCatching { startTicks(it.pid()) }.getOrNull() == started }
            ?.also { verifiedCommand(it.pid()) }
    }

    private fun readLaunch(process: ProcessHandle): Launch {
        val pid = process.pid()
        check(Files.isSameFile(File("/proc/$pid/cwd").toPath(), directory.toPath())) { "payment 작업 디렉터리가 일치해야 합니다." }
        val environment = File("/proc/$pid/environ").readText().split('\u0000').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return Launch(verifiedCommand(pid), environment)
    }

    private fun verifiedCommand(pid: Long): List<String> {
        val command = File("/proc/$pid/cmdline").readText().trimEnd('\u0000').split('\u0000')
        check(
            command.drop(1).take(2) == listOf("-jar", jar) && "--server.port=$port" in command &&
                "--spring.kafka.bootstrap-servers=$bootstrap" in command,
        ) {
            "payment 기동 명령이 이 실행의 JAR·포트·브로커와 일치해야 합니다."
        }
        return command
    }

    private fun startTicks(pid: Long) = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]

    private suspend fun awaitReadiness(process: Process) = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/health/readiness"))
            .timeout(Duration.ofSeconds(2)).build()
        withTimeout(180_000) {
            while (true) {
                check(process.isAlive) { "payment가 종료되었습니다(종료 코드 ${process.exitValue()})." }
                val response = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
                if (response?.statusCode() == 200 && Regex("\"status\"\\s*:\\s*\"UP\"").containsMatchIn(response.body())) return@withTimeout
                delay(500)
            }
        }
    }

    private data class Launch(val command: List<String>, val environment: Map<String, String>)
}
