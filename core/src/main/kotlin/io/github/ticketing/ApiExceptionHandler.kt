package io.github.ticketing

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 예외 → RFC 9457 ProblemDetail 변환은 이 클래스 한 곳에서만 한다. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(DomainException::class)
    fun handleDomain(e: DomainException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(e.status, e.message)
        problem.setProperty("code", e.code)
        e.properties.forEach { (key, value) -> problem.setProperty(key, value) }
        return ResponseEntity.status(e.status).body(problem)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.")
        problem.setProperty("code", "INVALID_REQUEST")
        problem.setProperty("errors", e.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) })
        return ResponseEntity.badRequest().body(problem)
    }
}
