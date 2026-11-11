package io.github.ticketing.settlement.application

import io.github.ticketing.booking.BookingCancelled
import io.github.ticketing.booking.BookingConfirmed
import io.github.ticketing.settlement.adapter.provided.event.BookingEventListener
import io.github.ticketing.settlement.application.port.provided.RecordRefundCommand
import io.github.ticketing.settlement.application.port.provided.RecordSaleCommand
import io.github.ticketing.settlement.application.port.required.SettlementCopyCommandPort
import io.github.ticketing.settlement.application.service.RecordBookingEventService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RecordBookingEventTests {
    private val time = Instant.parse("2026-11-10T00:00:00Z")
    private val receivedAt = Instant.parse("2026-11-11T00:00:00Z")
    private val copies = CopyStore()
    private val listener = BookingEventListener(RecordBookingEventService(copies, Clock.fixed(receivedAt, ZoneOffset.UTC)))

    @Test
    fun `확정 이벤트의 값을 사본에 기록하고 중복은 무시한다`() {
        val event = BookingConfirmed(1, 2, 3, 5, 6, listOf(4), BigDecimal.TEN, time)
        listener.on(event)
        listener.on(event)
        assertThat(copies.sales.values).containsExactly(
            RecordSaleCommand(1, 5, 6, 3, BigDecimal.TEN, time) to receivedAt,
        )
    }

    @Test
    fun `환불이 필요한 취소만 확정 사본 없이 기록한다`() {
        val event = BookingCancelled(1, 2, 3, 5, 6, "cancel", true, time)
        listener.on(event)
        listener.on(event)
        listener.on(event.copy(bookingId = 2, refundRequired = false))
        assertThat(copies.sales).isEmpty()
        assertThat(copies.refunds.values).containsExactly(RecordRefundCommand(1, 5, 6, "cancel", time) to receivedAt)
    }
}

private class CopyStore : SettlementCopyCommandPort {
    val sales = mutableMapOf<Long, Pair<RecordSaleCommand, Instant>>()
    val refunds = mutableMapOf<Long, Pair<RecordRefundCommand, Instant>>()

    override fun insertSaleIfAbsent(command: RecordSaleCommand, receivedAt: Instant): Boolean =
        sales.putIfAbsent(command.bookingId, command to receivedAt) == null

    override fun insertRefundIfAbsent(command: RecordRefundCommand, receivedAt: Instant): Boolean =
        refunds.putIfAbsent(command.bookingId, command to receivedAt) == null
}
