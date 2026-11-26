package io.github.ticketing.catalog.service

import io.github.ticketing.catalog.config.PosterStorageProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.MetadataDirective
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Duration
import java.util.UUID

@Service
class PosterUploadService(
    private val registration: CatalogRegistrationService,
    private val storage: S3Client,
    private val presigner: S3Presigner,
    private val properties: PosterStorageProperties,
) {
    fun issue(performanceId: Long, organizerId: Long, contentType: String): Upload {
        if (registration.posterKey(performanceId, organizerId) != null) throw PosterAlreadyAttachedException()
        if (contentType !in ALLOWED_TYPES) throw InvalidPosterTypeException()

        val uploadId = UUID.randomUUID()
        val key = stagingKey(performanceId, organizerId, uploadId)
        val signed = storageCall {
            presigner.presignPutObject { request ->
                request.signatureDuration(Duration.ofMinutes(5))
                    .putObjectRequest {
                        it.bucket(properties.bucket).key(key).contentType(contentType).ifNoneMatch("*")
                    }
            }
        }

        val headers = signed.signedHeaders().filterKeys { !it.equals("host", ignoreCase = true) }.mapValues { it.value.single() }
        return Upload(uploadId, signed.url().toExternalForm(), headers)
    }

    fun complete(performanceId: Long, organizerId: Long, uploadId: UUID): String {
        val publishedKey = "posters/$performanceId/$uploadId"
        val attached = registration.posterKey(performanceId, organizerId)
        if (attached == publishedKey) return requireNotNull(properties.publicUrl(publishedKey))
        if (attached != null) throw PosterAlreadyAttachedException()

        val key = stagingKey(performanceId, organizerId, uploadId)
        val head = storageCall { storage.headObject { it.bucket(properties.bucket).key(key) } }
        validateUploadedPoster(key, head)

        // 브라우저가 쓸 수 있는 키와 공개 키를 분리하고, 검증했던 버전만 복사한다.
        storageCall {
            storage.copyObject {
                it.sourceBucket(properties.bucket).sourceKey(key).copySourceIfMatch(head.eTag())
                    .destinationBucket(properties.bucket).destinationKey(publishedKey)
                    .metadataDirective(MetadataDirective.REPLACE).contentType(head.contentType())
            }
        }
        registration.attachPoster(performanceId, organizerId, publishedKey)
        return requireNotNull(properties.publicUrl(publishedKey))
    }

    private fun validateUploadedPoster(key: String, head: HeadObjectResponse) {
        if (head.contentLength() !in 1..MAX_BYTES) {
            storageCall { storage.deleteObject { it.bucket(properties.bucket).key(key) } }
            throw InvalidPosterSizeException()
        }
        if (head.contentType() !in ALLOWED_TYPES) throw InvalidPosterTypeException()
    }

    private fun stagingKey(performanceId: Long, organizerId: Long, uploadId: UUID) = "uploads/$organizerId/$performanceId/$uploadId"

    private fun <T> storageCall(operation: () -> T): T = try {
        operation()
    } catch (error: S3Exception) {
        if (error.statusCode() == HttpStatus.NOT_FOUND.value() || error.statusCode() == HttpStatus.PRECONDITION_FAILED.value()) {
            throw PosterUploadNotFoundException().also { it.initCause(error) }
        }
        throw PosterStorageUnavailableException(error)
    } catch (error: SdkException) {
        throw PosterStorageUnavailableException(error)
    }

    data class Upload(val uploadId: UUID, val url: String, val headers: Map<String, String>)

    companion object {
        private const val MAX_BYTES = 5L * 1024 * 1024
        private val ALLOWED_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
