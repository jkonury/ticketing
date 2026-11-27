package io.github.ticketing.catalog.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Duration

@ConfigurationProperties("ticketing.poster")
class PosterStorageProperties(
    val endpoint: URI,
    val uploadEndpoint: URI,
    val publicBaseUrl: URI,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
) {
    fun publicUrl(key: String?): String? = key?.let { "${publicBaseUrl.toString().trimEnd('/')}/$it" }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PosterStorageProperties::class)
class PosterStorageConfig(private val properties: PosterStorageProperties) {

    private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey, properties.secretKey))
    private val configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build()

    @Bean
    fun posterS3Client(): S3Client = S3Client.builder()
        .endpointOverride(properties.endpoint)
        .credentialsProvider(credentials)
        .region(Region.US_EAST_1)
        .serviceConfiguration(configuration)
        .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(API_TIMEOUT_SECONDS)) }
        .build()

    @Bean
    fun posterS3Presigner(): S3Presigner = S3Presigner.builder()
        .endpointOverride(properties.uploadEndpoint)
        .credentialsProvider(credentials)
        .region(Region.US_EAST_1)
        .serviceConfiguration(configuration)
        .build()

    companion object {
        private const val API_TIMEOUT_SECONDS = 5L
    }
}
