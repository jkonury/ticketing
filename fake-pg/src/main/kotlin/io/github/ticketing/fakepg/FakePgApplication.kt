package io.github.ticketing.fakepg

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FakePgApplication

fun main(args: Array<String>) {
    runApplication<FakePgApplication>(*args)
}
