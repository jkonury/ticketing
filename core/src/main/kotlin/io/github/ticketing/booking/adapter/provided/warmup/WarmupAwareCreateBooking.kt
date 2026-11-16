package io.github.ticketing.booking.adapter.provided.warmup

import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingResult
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

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
        if (SecurityContextHolder.getContext().authentication !is StartupWarmupAuthentication) return delegate.create(command)
        val conflict = when (command.idempotencyKey) {
            WARMUP_WINNER_KEY -> false
            WARMUP_CONFLICT_KEY -> true
            else -> throw AccessDeniedException("예열 템플릿이 아닙니다.")
        }
        if (command.scheduleId != 1L || command.seatIds != listOf(1L)) throw AccessDeniedException("예열 템플릿이 아닙니다.")
        val counts = WarmupIoCounts()
        try {
            return WarmupIo.measure(counts) { work.create(conflict) }
        } finally {
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.response?.setHeader(
                STARTUP_WARMUP_IO_HEADER,
                "db=${counts.dbStatements.get()};redis=${counts.redisCommands.get()}",
            )
        }
    }
}
