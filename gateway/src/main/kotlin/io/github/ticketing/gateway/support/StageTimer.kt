package io.github.ticketing.gateway.support

import io.micrometer.core.instrument.MeterRegistry
import reactor.core.publisher.Mono

/** 필터 단계별 소요 시간을 `ticketing.gateway.stage`로 기록한다. 병목이 어느 단계인지 가르는 용도다. */
fun <T : Any> Mono<T>.timedStage(registry: MeterRegistry, stage: String, route: String = "-"): Mono<T> = Mono.defer {
    val start = System.nanoTime()
    doFinally {
        registry.timer(
            "ticketing.gateway.stage",
            "stage",
            stage,
            "route",
            route,
        ).record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS)
    }
}
