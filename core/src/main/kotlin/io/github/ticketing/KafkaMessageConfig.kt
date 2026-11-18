package io.github.ticketing

import org.apache.kafka.common.header.Headers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter
import org.springframework.kafka.support.mapping.DefaultJacksonJavaTypeMapper
import tools.jackson.databind.json.JsonMapper

/**
 * 브로커 본문은 JSON 바이트 배열이고 타입 헤더(`__TypeId__`)는 붙이지 않는다(ADR 0043).
 * 헤더에 클래스 이름이 들어가면 payment가 분리되어 패키지·모듈이 달라질 때 소비자가 깨진다.
 * Modulith 외부화는 이 빈이 없으면 기본 변환기를 쓰는데, 기본 변환기는 타입 헤더를 붙인다.
 */
@Configuration(proxyBeanMethods = false)
class KafkaMessageConfig {
    @Bean
    fun kafkaMessageConverter(jsonMapper: JsonMapper): ByteArrayJacksonJsonMessageConverter =
        ByteArrayJacksonJsonMessageConverter(jsonMapper).apply { typeMapper = NoTypeHeaderMapper() }

    private class NoTypeHeaderMapper : DefaultJacksonJavaTypeMapper() {
        override fun fromClass(clazz: Class<*>, headers: Headers) = Unit
    }
}
