package io.github.ticketing.gateway.route

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.cloud.gateway.config.HttpClientCustomizer
import org.springframework.cloud.gateway.config.HttpClientFactory
import org.springframework.cloud.gateway.config.HttpClientProperties
import org.springframework.cloud.gateway.config.HttpClientSslConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.netty.resources.ConnectionProvider
import reactor.netty.transport.AddressUtils
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration

private const val DEFAULT_CORE_POOL_MAX_CONNECTIONS = 100
private const val DEFAULT_CORE_POOL_ACQUIRE_TIMEOUT_MS = 45_000L
private const val UNLIMITED_PENDING = -1

@ConfigurationProperties("ticketing.gateway.core-pool")
data class CorePoolProperties(
    val maxConnections: Int = DEFAULT_CORE_POOL_MAX_CONNECTIONS,
    val acquireTimeoutMs: Long = DEFAULT_CORE_POOL_ACQUIRE_TIMEOUT_MS,
)

/**
 * 기본 풀(elastic, 부모가 만든 설정)은 그대로 두고 core 호스트에만 연결 상한을 둔다.
 * Reactor Netty 풀 상한은 원격 호스트별로 걸리므로 전역 fixed 풀은 realtime SSE(장시간 연결)까지 막는다.
 *
 * 부모의 풀 구성(이름, max-idle-time, metrics 등)을 복제하지 않으려고 부모가 만든 provider를 `mutate()`로 되살려
 * 호스트별 설정만 덧붙인다. HttpClient는 URI의 원격 주소를 `AddressUtils.createUnresolved`로 만들어 풀 키로 쓰고
 * (`HttpClientConnect.URI_ADDRESS_MAPPER`), 호스트별 설정도 그 주소와 `equals`로 매칭된다.
 * 숫자 IP는 이 함수가 해결된 주소로 만들므로 `InetSocketAddress.createUnresolved`로 대신 만들면 어긋난다.
 */
class CorePooledHttpClientFactory(
    properties: HttpClientProperties,
    serverProperties: ServerProperties,
    sslConfigurer: HttpClientSslConfigurer,
    customizers: List<HttpClientCustomizer>,
    private val coreAddress: InetSocketAddress,
    private val corePool: CorePoolProperties,
) : HttpClientFactory(properties, serverProperties, sslConfigurer, customizers) {
    override fun buildConnectionProvider(properties: HttpClientProperties): ConnectionProvider {
        val base = super.buildConnectionProvider(properties)
        val builder = base.mutate() ?: return base
        base.dispose()
        return builder
            .forRemoteHost(coreAddress) { spec ->
                spec.maxConnections(corePool.maxConnections)
                    .pendingAcquireMaxCount(UNLIMITED_PENDING)
                    .pendingAcquireTimeout(Duration.ofMillis(corePool.acquireTimeoutMs))
            }.build()
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorePoolProperties::class)
class CorePoolConfiguration {
    @Bean
    fun gatewayHttpClientFactory(
        properties: HttpClientProperties,
        serverProperties: ServerProperties,
        customizers: List<HttpClientCustomizer>,
        sslConfigurer: HttpClientSslConfigurer,
        gateway: GatewayProperties,
        corePool: CorePoolProperties,
    ): HttpClientFactory {
        val uri = URI.create(gateway.coreUri)
        val port = if (uri.port != -1) {
            uri.port
        } else if (uri.scheme == "https") {
            HTTPS_PORT
        } else {
            HTTP_PORT
        }
        return CorePooledHttpClientFactory(
            properties,
            serverProperties,
            sslConfigurer,
            customizers,
            AddressUtils.createUnresolved(uri.host, port),
            corePool,
        )
    }
}

private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443
