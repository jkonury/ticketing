package io.github.ticketing.fakepg.payment.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.random.RandomGenerator

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FakePgProperties::class)
class FakePgConfig {

    // 현재 시각은 이 Clock으로만 얻는다. 테스트에서는 교체한다
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    // 시나리오 확률 판정용 난수 소스. 테스트에서 고정 구현으로 교체한다
    @Bean
    fun randomGenerator(): RandomGenerator = RandomGenerator.getDefault()

    // 지연 발송과 재시도를 요청 스레드 밖에서 실행한다. 작업은 Virtual Thread에서 돈다
    @Bean
    fun webhookScheduler(): TaskScheduler =
        SimpleAsyncTaskScheduler().apply {
            setVirtualThreads(true)
            setThreadNamePrefix("webhook-")
        }

    @Bean(destroyMethod = "close")
    fun webhookHttpClient(): HttpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build()
}
