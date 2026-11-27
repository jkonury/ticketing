package io.github.ticketing.settlement.application.port.required

import io.github.ticketing.settlement.application.port.provided.RecordRefundCommand
import io.github.ticketing.settlement.application.port.provided.RecordSaleCommand
import java.time.Instant

interface SettlementCopyCommandPort {
    fun insertSaleIfAbsent(command: RecordSaleCommand, receivedAt: Instant): Boolean
    fun insertRefundIfAbsent(command: RecordRefundCommand, receivedAt: Instant): Boolean
}
