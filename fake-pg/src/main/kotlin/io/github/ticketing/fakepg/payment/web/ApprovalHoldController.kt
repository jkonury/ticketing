package io.github.ticketing.fakepg.payment.web

import io.github.ticketing.fakepg.payment.service.ApprovalHolds
import io.github.ticketing.fakepg.payment.service.HoldState
import io.github.ticketing.fakepg.payment.service.PaymentService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** 승인 보류 테스트 제어(`fake-pg.test-controls.enabled=true`일 때만 등록). 결제 요청 전에 등록하고, 거래가 보류된 것을 확인한 뒤 해제한다. */
@RestController
@ConditionalOnProperty(prefix = "fake-pg.test-controls", name = ["enabled"], havingValue = "true")
@RequestMapping("/v1/test/approval-holds")
class ApprovalHoldController(private val holds: ApprovalHolds, private val paymentService: PaymentService) {

    /** 새로 등록하면 201, 이미 등록·보류 중이면 현재 상태를 200으로 돌려준다. 등록 상한을 넘으면 429. */
    @PostMapping("/{orderId}")
    fun register(@PathVariable orderId: String): ResponseEntity<HoldResponse> {
        val registration = holds.register(orderId) ?: throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "보류 등록 상한을 넘었다")
        val status = if (registration.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(HoldResponse(orderId, registration.state))
    }

    @GetMapping("/{orderId}")
    fun state(@PathVariable orderId: String): HoldResponse =
        HoldResponse(orderId, holds.state(orderId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "보류가 없다: $orderId"))

    @PostMapping("/{orderId}/release")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun release(@PathVariable orderId: String) {
        if (!paymentService.releaseHeldApproval(orderId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "보류된 거래가 없다: $orderId")
        }
    }

    @PostMapping("/{orderId}/fail")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun fail(@PathVariable orderId: String) {
        if (!paymentService.failHeldApproval(orderId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "보류된 거래가 없다: $orderId")
        }
    }
}

data class HoldResponse(val orderId: String, val state: HoldState)
