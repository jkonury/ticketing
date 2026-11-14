package io.github.ticketing.loadtest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/** k6 러너와 같은 환경 변수 계약을 읽는다. 러너(run.sh)가 넘기는 이름을 그대로 쓴다. */
object LoadEnv {
    private val mapper = ObjectMapper()

    val baseUrl: String = System.getenv("BASE_URL") ?: "http://localhost:18000"

    val jwtSecret: String
        get() = requireEnv("TICKETING_JWT_SECRET")

    fun requireEnv(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("$name 환경 변수가 필요합니다.")

    fun vus(defaultValue: Int): Int {
        val value = System.getenv("VUS")?.toIntOrNull() ?: defaultValue
        require(value >= 1) { "VUS는 1 이상의 정수여야 합니다: ${System.getenv("VUS")}" }
        return value
    }

    fun readJson(variable: String): JsonNode = mapper.readTree(Files.readString(Path.of(requireEnv(variable))))

    /** SUMMARY_FILE이 있으면 k6 --summary-export와 같은 `{"metrics":{이름:{"count":n}}}` 형태로 남긴다(metrics-delta.py가 읽는다). */
    fun writeSummary(counts: Map<String, Long>) {
        val path = System.getenv("SUMMARY_FILE")?.takeIf { it.isNotBlank() } ?: return
        val metrics = counts.mapValues { (_, count) -> mapOf("count" to count) }
        Files.writeString(Path.of(path), mapper.writeValueAsString(mapOf("metrics" to metrics)))
    }

    /** READINESS_FILE이 있으면 러너가 읽을 수 있게 JSON을 남긴다. */
    fun writeReadiness(report: Map<String, Any?>) {
        val path = System.getenv("READINESS_FILE")?.takeIf { it.isNotBlank() } ?: return
        Files.writeString(Path.of(path), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
    }
}
