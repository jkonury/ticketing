package io.github.ticketing

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** 예외 → RFC 9457 ProblemDetail 변환은 이 클래스 한 곳에서만 한다. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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

    @ExceptionHandler(
        HandlerMethodValidationException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleParameterValidation(): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.")
        problem.setProperty("code", "INVALID_REQUEST")
        return ResponseEntity.badRequest().body(problem)
    }

    /** 낙관적 락 충돌은 클라이언트가 다시 시도하면 해결되므로 409로 알린다. 이벤트 리스너 경로는 영향받지 않는다. */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "다른 요청과 동시에 변경되었습니다. 다시 시도해 주세요.")
        problem.setProperty("code", "CONCURRENT_MODIFICATION")
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
    }
}
