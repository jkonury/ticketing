package io.github.ticketing

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ClockConfig {

    // 현재 시각은 이 Clock으로만 얻는다. 테스트에서는 고정 Clock으로 교체한다
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
