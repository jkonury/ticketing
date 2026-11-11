package io.github.ticketing.booking.adapter.provided.web

import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.provided.BookingPage
import io.github.ticketing.booking.application.port.provided.BookingQueryUseCase
import io.github.ticketing.booking.application.port.provided.BookingResult
import io.github.ticketing.booking.application.port.provided.CancelBookingUseCase
import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import io.github.ticketing.booking.application.port.provided.RequestPaymentUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bookings")
@PreAuthorize("hasRole('USER')")
class BookingController(
    private val createBooking: CreateBookingUseCase,
    private val cancelBooking: CancelBookingUseCase,
    private val queries: BookingQueryUseCase,
    private val requestPayment: RequestPaymentUseCase,
) {
    @PostMapping
    fun create(
        authentication: Authentication,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 100) idempotencyKey: String,
        @Valid @RequestBody request: CreateBookingRequest,
    ): ResponseEntity<BookingResponse> {
        val result = createBooking.create(
            CreateBookingCommand(authentication.name.toLong(), idempotencyKey, request.scheduleId, request.seatIds),
        )

        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(BookingResponse.from(result.booking))
    }

    @GetMapping("/me")
    fun mine(
        authentication: Authentication,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "${BookingQueryUseCase.DEFAULT_PAGE_SIZE}") size: Int,
    ): BookingPageResponse = BookingPageResponse.from(queries.myBookings(authentication.name.toLong(), cursor, size))

    @GetMapping("/{id}")
    fun detail(authentication: Authentication, @PathVariable id: Long): BookingDetailResponse =
        BookingDetailResponse.from(queries.detail(authentication.name.toLong(), id))

    @PostMapping("/{id}/cancel")
    fun cancel(authentication: Authentication, @PathVariable id: Long): BookingResponse =
        BookingResponse.from(cancelBooking.cancel(authentication.name.toLong(), id))

    @PostMapping("/{id}/payments")
    @Suppress("UnusedParameter")
    fun pay(
        authentication: Authentication,
        @PathVariable id: Long,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 100) idempotencyKey: String,
    ): ResponseEntity<PaymentRequestResponse> {
        // PG 멱등 키는 예매 하나에 결제 하나가 되도록 서버가 정한다. 헤더 값은 재시도 식별용으로 받기만 한다
        val result = requestPayment.request(authentication.name.toLong(), id)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(PaymentRequestResponse(result.bookingId, result.status))
    }
}

data class PaymentRequestResponse(val bookingId: Long, val status: String)

data class CreateBookingRequest(val scheduleId: Long, @field:NotEmpty @field:Size(min = 1, max = 4) val seatIds: List<Long>)

data class BookingSeatResponse(val seatId: Long, val label: String, val price: java.math.BigDecimal)

data class BookingResponse(
    val bookingId: Long,
    val status: String,
    val totalAmount: java.math.BigDecimal,
    val expiresAt: java.time.Instant,
    val seats: List<BookingSeatResponse>,
) {
    companion object {
        fun from(result: BookingResult) = BookingResponse(
            result.bookingId,
            result.status,
            result.totalAmount,
            result.expiresAt,
            result.seats.map { BookingSeatResponse(it.seatId, it.label, it.price) },
        )
    }
}

data class BookingSummaryResponse(
    val bookingId: Long,
    val status: String,
    val performanceTitle: String,
    val startsAt: java.time.Instant,
    val seatCount: Int,
    val totalAmount: java.math.BigDecimal,
    val createdAt: java.time.Instant,
)

data class BookingPageResponse(val items: List<BookingSummaryResponse>, val nextCursor: Long?) {
    companion object {
        fun from(page: BookingPage) = BookingPageResponse(
            page.items.map {
                BookingSummaryResponse(
                    it.bookingId,
                    it.status,
                    it.performanceTitle,
                    it.startsAt,
                    it.seatCount,
                    it.totalAmount,
                    it.createdAt,
                )
            },
            page.nextCursor,
        )
    }
}

data class BookingDetailResponse(
    val bookingId: Long,
    val status: String,
    val performanceTitle: String,
    val startsAt: java.time.Instant,
    val totalAmount: java.math.BigDecimal,
    val expiresAt: java.time.Instant,
    val createdAt: java.time.Instant,
    val seats: List<BookingSeatResponse>,
) {
    companion object {
        fun from(view: BookingDetailView) = BookingDetailResponse(
            view.bookingId,
            view.status,
            view.performanceTitle,
            view.startsAt,
            view.totalAmount,
            view.expiresAt,
            view.createdAt,
            view.seats.map { BookingSeatResponse(it.seatId, it.label, it.price) },
        )
    }
}
