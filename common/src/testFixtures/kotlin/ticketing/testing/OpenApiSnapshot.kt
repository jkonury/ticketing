package ticketing.testing

import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object OpenApiSnapshot {
    private val mapper = JsonMapper.builder().enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build()

    fun verify(application: String, response: ByteArray) {
        val spec = mapper.readTree(response) as ObjectNode
        check(spec.path("paths").size() > 0) { "$application OpenAPI에 API 경로가 없다." }
        // springdoc가 요청에서 만든 호스트와 임시 테스트 포트는 HTTP 계약에 포함되지 않는다.
        spec.remove("servers")
        val actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n"
        val snapshot = Path.of("../docs/api/openapi/$application.json")

        if (System.getenv("UPDATE_OPENAPI") == "1") {
            snapshot.parent.createDirectories()
            if (!snapshot.exists() || snapshot.readText() != actual) snapshot.writeText(actual)
        } else {
            check(snapshot.exists() && snapshot.readText() == actual) {
                "$snapshot 계약이 달라졌다. 저장소 루트에서 UPDATE_OPENAPI=1 ./gradlew :$application:test 로 갱신한다."
            }
        }
    }
}
