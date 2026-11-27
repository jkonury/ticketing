package io.github.ticketing.e2e.support

import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals

class FakePgClient {
    private val url = System.getProperty("e2e.fakePgUrl", "http://localhost:18090")
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    fun registerHold(orderId: Long) = assertEquals(201, request("POST", "/v1/test/approval-holds/$orderId").statusCode())

    fun releaseHold(orderId: Long) = assertEquals(202, request("POST", "/v1/test/approval-holds/$orderId/release").statusCode())

    fun failHold(orderId: Long) = assertEquals(202, request("POST", "/v1/test/approval-holds/$orderId/fail").statusCode())

    fun holdState(orderId: Long): String? {
        val response = request("GET", "/v1/test/approval-holds/$orderId")
        return if (response.statusCode() == 200) json.readTree(response.body())["state"].asString() else null
    }

    private fun request(method: String, path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("$url$path"))
            .timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
}
