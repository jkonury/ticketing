package io.github.ticketing.catalog

import io.github.ticketing.IntegrationTestSupport
import io.github.ticketing.catalog.config.PosterStorageProperties
import io.github.ticketing.catalog.domain.SeatGrade
import io.github.ticketing.catalog.service.CatalogRegistrationService
import io.github.ticketing.catalog.service.CatalogRegistrationService.PriceSpec
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterPerformanceCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.RegisterVenueCommand
import io.github.ticketing.catalog.service.CatalogRegistrationService.SeatSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PosterUploadApiTests(
    private val mvc: MockMvcTester,
    private val registration: CatalogRegistrationService,
    private val storage: S3Client,
    private val properties: PosterStorageProperties,
) : IntegrationTestSupport() {
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun createBucket() {
        storage.createBucket { it.bucket(properties.bucket) }
    }

    @Test
    fun `소유 주최자만 포스터 업로드 URL을 발급받을 수 있다`() {
        val path = newUploadPath()
        assertThat(issue(path, role = "USER")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(issue(path, memberId = 2L)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(issue(path, contentType = "image/svg+xml")).hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_POSTER_TYPE")
        assertThat(complete(path, UUID.randomUUID().toString())).hasStatus(HttpStatus.NOT_FOUND)
            .bodyJson().extractingPath("$.code").isEqualTo("POSTER_UPLOAD_NOT_FOUND")
    }

    @Test
    fun `서명이 고정한 키와 콘텐츠 유형을 수정하면 저장소가 거부한다`() {
        val signed = signedUpload(newUploadPath())
        val original = signed["url"].asString()
        assertThat(put(signed, url = original.replace(signed["uploadId"].asString(), UUID.randomUUID().toString())))
            .isEqualTo(403)
        assertThat(put(signed, contentType = "image/png")).isEqualTo(403)
    }

    @Test
    fun `크기 초과 객체는 완료 확인에서 거부하고 삭제한다`() {
        val path = newUploadPath()
        val signed = signedUpload(path)
        val uploadId = signed["uploadId"].asString()
        assertThat(put(signed, bytes = ByteArray(5 * 1024 * 1024 + 1))).isEqualTo(200)

        assertThat(complete(path, uploadId)).hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.code").isEqualTo("INVALID_POSTER_SIZE")
        assertThat(complete(path, uploadId)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `완료한 포스터는 조회에 나타나며 같은 업로드의 완료는 재시도할 수 있다`() {
        val path = newUploadPath()
        val detail = path.substringBeforeLast('/')
        assertThat(mvc.get().uri(detail).exchange()).hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.posterUrl").isNull()
        val signed = signedUpload(path)
        val uploadId = signed["uploadId"].asString()
        assertThat(put(signed)).isEqualTo(200)
        val completed = complete(path, uploadId)
        assertThat(completed).hasStatus(HttpStatus.OK)
        val url = json.readTree(completed.response.contentAsString)["posterUrl"].asString()
        assertThat(complete(path, uploadId)).hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.posterUrl").isEqualTo(url)
        assertThat(mvc.get().uri(detail).exchange()).hasStatus(HttpStatus.OK)
            .bodyJson().extractingPath("$.posterUrl").isEqualTo(url)
    }

    private fun newUploadPath(): String {
        val venue = registration.registerVenue(RegisterVenueCommand("포스터 공연장", listOf(SeatSpec("A", "1", 1, SeatGrade.A))))
        val performance = registration.registerPerformance(
            RegisterPerformanceCommand(1, "포스터 공연", "설명", requireNotNull(venue.id), listOf(PriceSpec(SeatGrade.A, BigDecimal.TEN))),
        )
        return "/api/v1/performances/${performance.id}/poster-uploads"
    }

    private fun issue(path: String, role: String = "ORGANIZER", memberId: Long = 1L, contentType: String = "image/webp") =
        mvc.post().uri(path)
            .with(jwt().jwt { it.subject(memberId.toString()) }.authorities(SimpleGrantedAuthority("ROLE_$role")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(mapOf("contentType" to contentType)))
            .exchange()

    private fun complete(path: String, uploadId: String) = mvc.post().uri("$path/$uploadId/complete")
        .with(jwt().jwt { it.subject("1") }.authorities(SimpleGrantedAuthority("ROLE_ORGANIZER")))
        .exchange()

    private fun signedUpload(path: String): JsonNode {
        val issued = issue(path)
        assertThat(issued).hasStatus(HttpStatus.OK)
        return json.readTree(issued.response.contentAsString)
    }

    private fun put(
        signed: JsonNode,
        url: String = signed["url"].asString(),
        contentType: String? = null,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ): Int {
        val request = HttpRequest.newBuilder(URI(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        signed["headers"].properties().forEach { (name, value) -> request.header(name, value.asString()) }
        if (contentType != null) request.setHeader("content-type", contentType)
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    companion object {
        private const val ACCESS_KEY = "poster-test"
        private const val SECRET_KEY = "poster-test-secret"
        private val objectStorage by lazy {
            GenericContainer(
                "ghcr.io/rustfs/rustfs:1.0.6@sha256:ee717072290bec9b11abfc0365f8310b00df1cd9b3639943edae47bdef05044a",
            )
                .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
                .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/health/ready"))
                .apply { start() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun posterProperties(registry: DynamicPropertyRegistry) {
            registry.add("ticketing.poster.endpoint") { "http://${objectStorage.host}:${objectStorage.firstMappedPort}" }
            registry.add("ticketing.poster.upload-endpoint") { "http://${objectStorage.host}:${objectStorage.firstMappedPort}" }
            registry.add("ticketing.poster.public-base-url") { "http://${objectStorage.host}:${objectStorage.firstMappedPort}/poster-test" }
            registry.add("ticketing.poster.bucket") { "poster-test" }
            registry.add("ticketing.poster.access-key") { ACCESS_KEY }
            registry.add("ticketing.poster.secret-key") { SECRET_KEY }
        }
    }
}
