package io.github.ticketing.realtime.support

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Clock

@Configuration
class RealtimeSupport {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

class RealtimeException(val status: HttpStatus, val code: String) : RuntimeException(code)

fun memberId(header: String?): Long = header?.toLongOrNull() ?: throw RealtimeException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED")

@RestControllerAdvice
class RealtimeExceptionHandler {
    @ExceptionHandler(RealtimeException::class)
    fun handle(exception: RealtimeException): ProblemDetail = ProblemDetail.forStatusAndDetail(exception.status, exception.code).apply {
        setProperty("code", exception.code)
    }
}
