package io.github.ticketing.gateway.support

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

fun ServerWebExchange.writeProblem(status: HttpStatus, code: String, detail: String): Mono<Void> {
    response.statusCode = status
    response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
    val body = """{"status":${status.value()},"detail":"$detail","code":"$code"}""".toByteArray(Charsets.UTF_8)
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)))
}
