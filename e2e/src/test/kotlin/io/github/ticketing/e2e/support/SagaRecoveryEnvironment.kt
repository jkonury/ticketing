package io.github.ticketing.e2e.support

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal fun sagaProperty(key: String): String =
    checkNotNull(System.getProperty("e2e.$key")) { "e2e.$key 설정이 필요합니다(scripts/e2e/run.sh --saga-recovery)." }

internal fun runDirectory() = File(sagaProperty("sagaDir"))

internal fun File.writeAtomically(text: String) {
    val temporary = File.createTempFile("$name-", ".tmp", parentFile)
    try {
        temporary.writeText(text)
        Files.move(temporary.toPath(), toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}

internal class SagaBroker {
    private val composeProject = sagaProperty("composeProject")
    private val containerId = sagaProperty("kafkaContainer")
    private val bootstrap = sagaProperty("kafkaBootstrap")
    private val marker = File(runDirectory(), "kafka-paused")

    fun pause() {
        check(!marker.exists()) { "이 실행에 이미 Kafka 복구 대상이 기록되어 있습니다." }
        check(!isPaused()) { "Kafka가 이미 일시 중지되어 있습니다." }
        marker.writeAtomically("$containerId\n")
        compose("pause", "kafka")
        check(isPaused()) { "Kafka가 일시 중지되지 않았습니다." }
    }

    fun resume() {
        if (!marker.exists()) return
        check(marker.readText().trim() == containerId) { "Kafka 복구 대상이 달라졌습니다." }
        if (isPaused()) {
            compose("unpause", "kafka")
            check(!isPaused()) { "Kafka가 아직 일시 중지되어 있습니다." }
        }
        Files.delete(marker.toPath())
    }

    private fun isPaused(): Boolean {
        check(containerId.matches(Regex("[a-f0-9]{64}")))
        check(compose("ps", "--all", "--no-trunc", "-q", "kafka") == containerId) { "Compose Kafka 컨테이너가 바뀌었습니다." }
        val format = "{{index .Config.Labels \"com.docker.compose.project\"}} " +
            "{{index .Config.Labels \"com.docker.compose.service\"}} {{.State.Running}} {{.State.Paused}} " +
            "{{range (index .NetworkSettings.Ports \"9092/tcp\")}}{{.HostIp}}:{{.HostPort}}{{end}}"
        val binding = "127.0.0.1:${bootstrap.substringAfter(':')}"
        return when (val state = docker("inspect", "--format", format, containerId)) {
            "$composeProject kafka true false $binding" -> false
            "$composeProject kafka true true $binding" -> true
            else -> error("Kafka 프로젝트·서비스·상태가 일치하지 않습니다: $state")
        }
    }

    private fun compose(vararg args: String) = docker("compose", "-p", composeProject, *args)

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder("timeout", "--kill-after=2s", "15s", "docker", *args)
            .directory(File(sagaProperty("projectDir"))).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0) { "docker ${args.first()} 실패: $output" }
        return output
    }
}
