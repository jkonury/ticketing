package io.github.ticketing.gateway.ratelimit

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.InetAddress
import java.security.MessageDigest

internal const val CLIENT_IP_HEADER = "X-Ticketing-Client-IP"
internal const val INGRESS_PROOF_HEADER = "X-Ticketing-Ingress-Proof"
internal const val VERIFIED_CLIENT_IP = "ticketing.gateway.verified-client-ip"

private val ingressProofFormat = Regex("[0-9a-f]{64}")
private val addressCharacters = Regex("[0-9a-fA-F:.]+")
private val ipv4Format = Regex("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")
private const val MAX_IP_LENGTH = 45
private val clientAddressHeaders = setOf(
    "forwarded", "x-real-ip", "x-client-ip", "x-cluster-client-ip", "client-ip", "true-client-ip", "cf-connecting-ip",
    CLIENT_IP_HEADER.lowercase(), INGRESS_PROOF_HEADER.lowercase(),
)

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ClientAddressIngressFilter(@Value("\${ticketing.gateway.ingress-proof:}") proof: String) : WebFilter {
    private val expectedProof = proof.takeIf(ingressProofFormat::matches)?.toByteArray()

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val source = exchange.request.headers
        val toRemove = source.headerNames().filter { name ->
            val normalized = name.lowercase()
            normalized.startsWith("x-forwarded-") || normalized in clientAddressHeaders
        }
        if (toRemove.isEmpty()) return chain.filter(exchange)

        val address = verifiedAddress(source)
        if (address != null) exchange.attributes[VERIFIED_CLIENT_IP] = address
        val request = exchange.request.mutate().headers { headers -> toRemove.forEach(headers::remove) }.build()
        return chain.filter(exchange.mutate().request(request).build())
    }

    private fun verifiedAddress(headers: HttpHeaders): String? {
        val supplied = headers[INGRESS_PROOF_HEADER]?.singleOrNull()
        if (!matchesProof(supplied)) return null
        return headers[CLIENT_IP_HEADER]?.singleOrNull()?.let(::parseAddress)
    }

    private fun matchesProof(supplied: String?): Boolean = expectedProof != null && supplied != null &&
        ingressProofFormat.matches(supplied) && MessageDigest.isEqual(expectedProof, supplied.toByteArray())

    private fun parseAddress(address: String): String? {
        val valid = if (':' in address) {
            address.length <= MAX_IP_LENGTH && addressCharacters.matches(address)
        } else {
            ipv4Format.matches(address)
        }
        if (!valid) return null
        return try {
            InetAddress.ofLiteral(address).hostAddress
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
