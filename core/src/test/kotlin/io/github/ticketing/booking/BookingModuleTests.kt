package io.github.ticketing.booking

import io.github.ticketing.TestcontainersConfiguration
import io.github.ticketing.booking.application.port.provided.CreateBookingCommand
import io.github.ticketing.booking.application.port.provided.CreateBookingUseCase
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsCommand
import io.github.ticketing.booking.application.port.provided.RegisterScheduleSeatsUseCase
import io.github.ticketing.booking.application.port.provided.RequestPaymentUseCase
import io.github.ticketing.payment.PaymentRequested
import io.github.ticketing.payment.PaymentSucceeded
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** booking 빈만 띄운다. payment는 이벤트 타입만 쓰므로 결제 결과는 이벤트를 직접 발행해 흉내 낸다. */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
class BookingModuleTests {
    @Autowired lateinit var register: RegisterScheduleSeatsUseCase

    @Autowired lateinit var createBooking: CreateBookingUseCase

    @Autowired lateinit var requestPayment: RequestPaymentUseCase

    private fun newBooking(): Triple<Long, Long, BigDecimal> {
        val scheduleId = System.nanoTime()
        val price = BigDecimal(100_000)
        register.register(
            RegisterScheduleSeatsCommand(
                scheduleId, 1, "모듈 테스트 공연", 2, Instant.now().plusSeconds(86_400),
                listOf(RegisterScheduleSeatsCommand.SeatInfo(1, "R", "A구역 1번", price)),
            ),
        )
        val memberId = 7L
        val result = createBooking.create(CreateBookingCommand(memberId, UUID.randomUUID().toString(), scheduleId, listOf(1)))
        return Triple(result.booking.bookingId, memberId, price)
    }

    @Test
    fun `결제를 요청하면 PaymentRequested가 발행된다`(scenario: Scenario) {
        val (bookingId, memberId, price) = newBooking()

        scenario.stimulate(Runnable { requestPayment.request(memberId, bookingId) })
            .andWaitForEventOfType(PaymentRequested::class.java)
            .matching { it.orderId == bookingId }
            .toArriveAndVerify { event ->
                assertThat(event.memberId).isEqualTo(memberId)
                assertThat(event.amount).isEqualByComparingTo(price)
                assertThat(event.idempotencyKey).isEqualTo("booking-$bookingId")
            }
    }

    @Test
    fun `PaymentSucceeded를 받으면 BookingConfirmed가 발행된다`(scenario: Scenario) {
        val (bookingId, memberId, price) = newBooking()
        requestPayment.request(memberId, bookingId)

        scenario.publish(PaymentSucceeded(5_000, bookingId, price, Instant.now()))
            .andWaitForEventOfType(BookingConfirmed::class.java)
            .matching { it.bookingId == bookingId }
            .toArriveAndVerify { event ->
                assertThat(event.memberId).isEqualTo(memberId)
                assertThat(event.amount).isEqualByComparingTo(price)
            }
    }
}
