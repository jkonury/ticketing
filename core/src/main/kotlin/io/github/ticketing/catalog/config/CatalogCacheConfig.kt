package io.github.ticketing.catalog.config

import io.github.ticketing.catalog.query.PerformanceDetail
import io.github.ticketing.catalog.query.SeatLayout
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
import org.springframework.data.redis.serializer.StringRedisSerializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Duration

object CatalogCaches {
    // Redis를 다른 모듈과 함께 쓰므로 catalog: 접두사로 키 공간을 분리한다
    const val PERFORMANCE = "catalog:performance"
    const val SCHEDULE_SEATS = "catalog:schedule-seats"
}

@Configuration(proxyBeanMethods = false)
@EnableCaching
class CatalogCacheConfig {

    // 타입 정보를 JSON에 넣는 default typing 대신 캐시별로 값 타입을 고정한다(역직렬화 가젯 위험 방지)
    @Bean
    fun catalogCacheManager(connectionFactory: RedisConnectionFactory): RedisCacheManager {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        fun <T : Any> config(type: Class<T>) = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues()
            .serializeKeysWith(SerializationPair.fromSerializer(StringRedisSerializer.UTF_8))
            .serializeValuesWith(SerializationPair.fromSerializer(JacksonJsonRedisSerializer(mapper, type)))

        return RedisCacheManager.builder(connectionFactory)
            .withCacheConfiguration(CatalogCaches.PERFORMANCE, config(PerformanceDetail::class.java))
            .withCacheConfiguration(CatalogCaches.SCHEDULE_SEATS, config(SeatLayout::class.java))
            .disableCreateOnMissingCache()
            // 쓰기 트랜잭션 안의 evict는 커밋 뒤에 실행해, 커밋 전에 다른 요청이 옛 값을 다시 채우는 일을 막는다
            .transactionAware()
            .build()
    }
}
