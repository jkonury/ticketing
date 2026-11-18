package io.github.ticketing

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * 앱 공용 빈. 루트 패키지가 다른 앱(payment 등)에서도 쓸 수 있게 auto-configuration으로 노출한다.
 *
 * [ApiExceptionHandler]는 `@Configuration`이 아니라 `AutoConfigurationExcludeFilter`가 스캔에서 빼지 않는다.
 * 루트 패키지가 같은 앱(core)에서는 컴포넌트 스캔이 먼저 등록한 빈(`apiExceptionHandler`)을 쓰고
 * imports 항목은 같은 클래스라 무시된다. 빈이 하나인지는 core의 `CommonBeansTests`가 보장한다.
 */
@AutoConfiguration
class CommonAutoConfiguration {

    // 현재 시각은 이 Clock으로만 얻는다. 테스트에서는 고정 Clock으로 교체한다
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()
}
