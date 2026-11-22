package io.github.ticketing.payment.adapter.provided.web

import io.github.ticketing.payment.application.port.provided.PaymentQueryUseCase
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class PaymentQueryController(private val payments: PaymentQueryUseCase) {
    @GetMapping("/api/v1/payments/me")
    @PreAuthorize("hasRole('USER')")
    fun mine(authentication: Authentication, @RequestParam @Size(min = 1, max = 50) bookingIds: List<Long>): List<PaymentStatusResponse> =
        payments.findMine(authentication.name.toLong(), bookingIds).map {
            PaymentStatusResponse(it.bookingId, it.status, it.failureReason, it.approvedAt, it.refundedAt)
        }
}

data class PaymentStatusResponse(
    val bookingId: Long,
    val status: String,
    val failureReason: String?,
    val approvedAt: Instant?,
    val refundedAt: Instant?,
)
