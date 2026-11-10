package io.github.ticketing

import org.springframework.http.HttpStatus

/**
 * 도메인 오류의 공통 부모. 각 모듈이 상속해 안정적인 [code]와 HTTP 상태를 정한다.
 * 모듈 내부 예외 타입을 몰라도 [ApiExceptionHandler] 한 곳에서 응답으로 변환할 수 있다.
 */
abstract class DomainException(
    val code: String,
    val status: HttpStatus,
    message: String,
) : RuntimeException(message) {
    /** 응답 ProblemDetail에 추가로 실을 값. 예: 선점 실패한 좌석 ID 목록 */
    open val properties: Map<String, Any> = emptyMap()
}
