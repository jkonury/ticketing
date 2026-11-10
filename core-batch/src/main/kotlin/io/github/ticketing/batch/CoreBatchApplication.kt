package io.github.ticketing.batch

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// core의 모듈 패키지를 스캔하려면 M2에서 scanBasePackages와 Job 설정을 정한다
@SpringBootApplication
class CoreBatchApplication

fun main(args: Array<String>) {
    runApplication<CoreBatchApplication>(*args)
}
