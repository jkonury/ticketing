package io.github.ticketing.booking.adapter.provided.web

import io.github.ticketing.booking.application.port.provided.AdminBookingFilter
import io.github.ticketing.booking.application.port.provided.AdminBookingQueryUseCase
import io.github.ticketing.booking.application.port.provided.AdminBookingSummary
import io.github.ticketing.booking.application.port.provided.BookingCouponView
import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.provided.BookingQueryUseCase
import io.github.ticketing.booking.application.port.provided.CancelBookingUseCase
import io.github.ticketing.booking.domain.BookingStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springdoc.core.annotations.ParameterObject
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

@RestController
@RequestMapping("/api/v1/admin/bookings")
@PreAuthorize("hasRole('ADMIN')")
class AdminBookingController(private val queries: AdminBookingQueryUseCase, private val cancellations: CancelBookingUseCase) {
    @GetMapping
    fun list(@Valid @ModelAttribute @ParameterObject request: AdminBookingListRequest): AdminBookingPageResponse {
        val page = queries.list(request.toFilter())
        return AdminBookingPageResponse(page.items.map(AdminBookingSummaryResponse::from), page.nextCursor)
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): AdminBookingDetailResponse = AdminBookingDetailResponse.from(queries.detail(id))

    @PostMapping("/{id}/cancellation")
    fun forceCancel(
        authentication: Authentication,
        @PathVariable id: Long,
        @Valid @RequestBody request: ForceCancelBookingRequest,
    ): BookingResponse = BookingResponse.from(cancellations.forceCancel(authentication.name.toLong(), id, request.reason))
}

class ForceCancelBookingRequest(reason: String) {
    @field:NotBlank
    @field:Size(max = 200)
    val reason: String = reason.trim()
}

data class AdminBookingListRequest(
    @field:Positive val memberId: Long? = null,
    @field:Positive val scheduleId: Long? = null,
    val status: BookingStatus? = null,
    @field:Schema(description = "예매 생성 시각 하한(포함)") val createdFrom: Instant? = null,
    @field:Schema(description = "예매 생성 시각 상한(포함)") val createdTo: Instant? = null,
    @field:Positive val cursor: Long? = null,
    @field:Min(1) @field:Max(BookingQueryUseCase.MAX_PAGE_SIZE.toLong())
    @field:Schema(defaultValue = "${BookingQueryUseCase.DEFAULT_PAGE_SIZE}")
    val size: Int = BookingQueryUseCase.DEFAULT_PAGE_SIZE,
) {
    fun toFilter() = AdminBookingFilter(memberId, scheduleId, status, createdFrom, createdTo, cursor, size)
}

data class AdminBookingPageResponse(val items: List<AdminBookingSummaryResponse>, val nextCursor: Long?)

data class AdminBookingSummaryResponse(
    val bookingId: Long,
    val memberId: Long,
    val scheduleId: Long,
    val performanceTitle: String,
    val status: BookingStatus,
    val totalAmount: BigDecimal,
    val createdAt: Instant,
) {
    companion object {
        fun from(view: AdminBookingSummary) = AdminBookingSummaryResponse(
            view.bookingId,
            view.memberId,
            view.scheduleId,
            view.performanceTitle,
            view.status,
            view.totalAmount,
            view.createdAt,
        )
    }
}

data class AdminBookingDetailResponse(
    val bookingId: Long,
    val memberId: Long,
    val scheduleId: Long,
    val status: BookingStatus,
    val performanceTitle: String,
    val startsAt: Instant,
    val listAmount: BigDecimal,
    val discountAmount: BigDecimal,
    val payableAmount: BigDecimal,
    val coupon: BookingCouponView?,
    val expiresAt: Instant,
    val createdAt: Instant,
    val seats: List<BookingSeatResponse>,
) {
    companion object {
        fun from(view: BookingDetailView) = AdminBookingDetailResponse(
            bookingId = view.bookingId,
            memberId = view.memberId,
            scheduleId = view.scheduleId,
            status = BookingStatus.valueOf(view.status),
            performanceTitle = view.performanceTitle,
            startsAt = view.startsAt,
            listAmount = view.listAmount,
            discountAmount = view.discountAmount,
            payableAmount = view.payableAmount,
            coupon = view.coupon,
            expiresAt = view.expiresAt,
            createdAt = view.createdAt,
            seats = view.seats.map { BookingSeatResponse(it.seatId, it.label, it.price) },
        )
    }
}
