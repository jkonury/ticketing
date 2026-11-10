package io.github.ticketing.payment.adapter.required.pg

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FakePgClientProperties::class)
class FakePgClientConfig {
    @Bean
    fun fakePgAdapter(properties: FakePgClientProperties): FakePgAdapter = FakePgAdapter(fakePgRestClient(properties))

    companion object {
        /** 타임아웃이 없으면 PG가 응답하지 않을 때 리스너 스레드가 무한히 묶인다 */
        fun fakePgRestClient(properties: FakePgClientProperties, builder: RestClient.Builder = RestClient.builder()): RestClient {
            val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }
            return builder.baseUrl(properties.baseUrl).requestFactory(requestFactory).build()
        }
    }
}
