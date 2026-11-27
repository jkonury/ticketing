package io.github.ticketing.settlement.application.service

import io.github.ticketing.settlement.application.port.provided.RecordBookingEventUseCase
import io.github.ticketing.settlement.application.port.provided.RecordRefundCommand
import io.github.ticketing.settlement.application.port.provided.RecordSaleCommand
import io.github.ticketing.settlement.application.port.required.SettlementCopyCommandPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional
class RecordBookingEventService(private val copies: SettlementCopyCommandPort, private val clock: Clock) : RecordBookingEventUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun recordSale(command: RecordSaleCommand) {
        if (!copies.insertSaleIfAbsent(command, clock.instant())) {
            log.debug("이미 기록된 예매 확정: bookingId={}", command.bookingId)
        }
    }

    override fun recordRefund(command: RecordRefundCommand) {
        if (!copies.insertRefundIfAbsent(command, clock.instant())) {
            log.debug("이미 기록된 환불 취소: bookingId={}", command.bookingId)
        }
    }
}
