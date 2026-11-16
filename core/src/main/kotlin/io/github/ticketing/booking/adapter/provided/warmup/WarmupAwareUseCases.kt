package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.booking.application.port.provided.BookingDetailView
import io.github.ticketing.booking.application.port.provided.BookingPage
import io.github.ticketing.booking.application.port.provided.BookingQueryUseCase
import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingResult
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import io.github.ticketing.booking.application.port.provided.PaymentRequestResult
import io.github.ticketing.booking.application.port.provided.RequestPaymentUseCase
import io.github.ticketing.payment.PaymentWarmupRequested
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Primary
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** 예열 경로의 고정 템플릿. 이 밖의 요청은 예열 principal이라도 실제 유스케이스에 닿지 못한다. */
internal const val WARMUP_BOOKING_ID = -1L
private const val MAX_TEMPLATE_SEATS = 4

internal fun isWarmupPrincipal(): Boolean = SecurityContextHolder.getContext().authentication is StartupWarmupAuthentication

/** 호출 중 발생한 DB·Redis 호출 수를 응답 헤더로 돌려준다. Gateway가 호출량을 집계한다. */
internal fun <T> measuredWarmup(action: () -> T): T {
    val counts = WarmupIoCounts()
    try {
        return WarmupIo.measure(counts, action)
    } finally {
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.response?.setHeader(
            STARTUP_WARMUP_IO_HEADER,
            "db=${counts.dbStatements.get()};redis=${counts.redisCommands.get()}",
        )
    }
}

/**
 * 컨트롤러 아래의 가장자리 분기다. 예열 principal([StartupWarmupAuthentication])의 요청만 음수 회차 격리 환경으로 보내고,
 * 그 밖의 모든 요청은 실제 유스케이스를 그대로 호출한다. 예열 principal이라도 고정 템플릿이 아니면 실제 유스케이스에 닿지 못한다.
 */
@Primary
@Component
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class WarmupAwareCreateBooking(
    @Qualifier("createBookingService") private val delegate: CreateBookingUseCase,
    private val work: BookingWarmupWork,
) : CreateBookingUseCase {
    override fun create(command: CreateBookingCommand): CreateBookingResult {
        if (!isWarmupPrincipal()) return delegate.create(command)
        val conflict = when (command.idempotencyKey) {
            WARMUP_WINNER_KEY -> false
            WARMUP_CONFLICT_KEY -> true
            else -> throw AccessDeniedException("예열 템플릿이 아닙니다.")
        }
        val seatCount = command.seatIds.size
        val template = (1L..MAX_TEMPLATE_SEATS).take(seatCount.coerceAtLeast(1))
        if (command.scheduleId != 1L || seatCount !in 1..MAX_TEMPLATE_SEATS || command.seatIds != template) {
            throw AccessDeniedException("예열 템플릿이 아닙니다.")
        }
        return measuredWarmup { work.create(conflict, seatCount) }
    }
}

/** 예열 principal의 `GET /api/v1/bookings/-1`만 격리 환경의 상세 조회로 보낸다. */
@Primary
@Component
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class WarmupAwareBookingQuery(
    @Qualifier("bookingQueryService") private val delegate: BookingQueryUseCase,
    private val work: BookingWarmupWork,
) : BookingQueryUseCase {
    override fun myBookings(memberId: Long, cursor: Long?, size: Int, performanceId: Long?): BookingPage {
        if (isWarmupPrincipal()) throw AccessDeniedException("예열 템플릿이 아닙니다.")
        return delegate.myBookings(memberId, cursor, size, performanceId)
    }

    override fun detail(memberId: Long, bookingId: Long): BookingDetailView {
        if (!isWarmupPrincipal()) return delegate.detail(memberId, bookingId)
        if (bookingId != WARMUP_BOOKING_ID) throw AccessDeniedException("예열 템플릿이 아닙니다.")
        return measuredWarmup { work.detail() }
    }
}

/**
 * 예열 principal의 `POST /api/v1/bookings/-1/payments`만 격리 환경의 결제 요청으로 보낸다. 롤백이 끝난 뒤 payment 모듈에
 * 이벤트로 PG 송신·웹훅 예열을 요청한다(모듈 간 호출은 이벤트로만 한다).
 */
@Primary
@Component
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class WarmupAwareRequestPayment(
    @Qualifier("requestPaymentService") private val delegate: RequestPaymentUseCase,
    private val work: BookingWarmupWork,
    private val events: ApplicationEventPublisher,
) : RequestPaymentUseCase {
    override fun request(memberId: Long, bookingId: Long): PaymentRequestResult {
        if (!isWarmupPrincipal()) return delegate.request(memberId, bookingId)
        if (bookingId != WARMUP_BOOKING_ID) throw AccessDeniedException("예열 템플릿이 아닙니다.")
        return measuredWarmup {
            work.requestPayment().also { events.publishEvent(PaymentWarmupRequested()) }
        }
    }
}
