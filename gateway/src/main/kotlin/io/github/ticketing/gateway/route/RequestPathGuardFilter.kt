package io.github.ticketing.gateway.route

import io.github.ticketing.gateway.support.writeProblem
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.UriUtils
import reactor.core.publisher.Mono

internal enum class PathVerdict { ALLOWED, AMBIGUOUS, WEBHOOK }

private val webhookPrefix = listOf("api", "v1", "payments", "webhooks")

/**
 * 라우트 선택과 JWT 검증보다 먼저 요청 경로를 판정한다.
 * - 모호한 경로(중복 슬래시, 인코딩된 구분자·점 세그먼트, 경로 매개변수)는 400으로 거부한다. Gateway는 라우트를 고른 뒤 전달 URL을
 *   다시 만들면서 중복 슬래시를 지우므로, 하위 앱이 원래 경로를 볼 수 없어 차단 판정과 전달 경로가 어긋날 수 있다.
 * - 웹훅(`/api/v1/payments/webhooks` 하위)은 정규화한 경로(퍼센트 디코딩, 대소문자 무시, 후행 슬래시)로 판정해 404로 막는다.
 *   웹훅은 fake-pg가 payment 앱을 직접 호출하므로 Gateway는 어떤 인증 상태에서도 전달하지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestPathGuardFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        when (classifyPath(exchange.request.uri.rawPath)) {
            PathVerdict.ALLOWED -> chain.filter(exchange)
            PathVerdict.AMBIGUOUS -> exchange.writeProblem(HttpStatus.BAD_REQUEST, "INVALID_PATH", "요청 경로가 올바르지 않습니다.")
            PathVerdict.WEBHOOK -> exchange.writeProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "존재하지 않는 경로입니다.")
        }
}

internal fun classifyPath(rawPath: String): PathVerdict {
    val segments = normalizedSegments(rawPath) ?: return PathVerdict.AMBIGUOUS
    return if (segments.take(webhookPrefix.size) == webhookPrefix) PathVerdict.WEBHOOK else PathVerdict.ALLOWED
}

/** 퍼센트 디코딩하고 소문자로 만든 세그먼트. 모호한 경로면 null. */
private fun normalizedSegments(rawPath: String): List<String>? {
    if (rawPath.isEmpty() || rawPath == "/") return emptyList()
    val raw = rawPath.removePrefix("/").split('/').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    val decoded = raw.map { segment -> segment.takeIf { it.isNotEmpty() && it.none { c -> c == ';' || c == '\\' } }?.let(::decodeSegment) }
    return if (null in decoded) null else decoded.filterNotNull().map { it.lowercase() }
}

private fun decodeSegment(segment: String): String? {
    val decoded = try {
        UriUtils.decode(segment, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val ambiguous = decoded == "." || decoded == ".." || decoded.any { it == '/' || it == '\\' || it == ';' }
    return decoded.takeUnless { ambiguous }
}
