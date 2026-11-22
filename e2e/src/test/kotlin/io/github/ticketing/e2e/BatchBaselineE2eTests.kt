package io.github.ticketing.e2e

import io.github.ticketing.e2e.support.BatchSupport
import io.github.ticketing.e2e.support.BatchTarget
import io.github.ticketing.e2e.support.E2eClient
import io.github.ticketing.e2e.support.Member
import io.github.ticketing.e2e.support.Schedule
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M6 분리 전후 비교용 Batch 기준선. core-batch(정산·미결제 정리)와 payment-batch(결제 대사)를 별도 프로세스로 실행해 E2E로 만든 데이터의 결과를 고정한다.
 * 같은 DB에 이전 실행 데이터가 남으므로 이 테스트가 만든 회차·예매로만 검증한다.
 */
class BatchBaselineE2eTests {
    private val client = E2eClient(System.getProperty("e2e.gatewayUrl", "http://localhost:18000"))
    private val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

    private class Sales(val schedule: Schedule, val plain: Long, val discounted: Long, val refunded: Long)

    /** 정가 10000 좌석 3개: 일반 확정 1, 쿠폰(3000) 확정 1, 확정 후 환불 1. */
    private suspend fun createSales(): Sales {
        val admin = client.adminLogin()
        val organizer = client.signUp("ORGANIZER")
        val schedule = client.createSchedule(organizer, 3)
        val seats = schedule.seatIds

        val plainBuyer = client.signUp("USER")
        val plain = client.reserve(plainBuyer, schedule, seats.take(1))
        client.payAndAwaitConfirmed(plainBuyer, plain)

        val couponBuyer = client.signUp("USER")
        val definitionId = client.createFixedCoupon(admin, DISCOUNT)
        client.grantCoupon(admin, definitionId, couponBuyer.id)
        val issued = client.myCoupon(couponBuyer, definitionId)["id"].asLong()
        val discounted = client.reserve(couponBuyer, schedule, seats.slice(1..1))
        client.applyCoupon(couponBuyer, discounted, issued)
        client.poll { client.bookingDetail(couponBuyer, discounted)["coupon"]["state"].asString() == "RESERVED" }
        client.payAndAwaitConfirmed(couponBuyer, discounted)

        val refundBuyer = client.signUp("USER")
        val refunded = client.reserve(refundBuyer, schedule, seats.slice(2..2))
        client.payAndAwaitConfirmed(refundBuyer, refunded)
        client.cancelBooking(refundBuyer, refunded)
        client.poll { client.bookingDetail(refundBuyer, refunded)["status"].asString() == "CANCELLED" }
        client.poll { BatchSupport.paymentPsql("SELECT status FROM payment.payment WHERE order_id = $refunded") == listOf("REFUNDED") }
        client.poll {
            // 정산 복사본(이벤트 비동기 수신)이 판매 3건·환불 1건을 모두 받을 때까지 기다린다
            BatchSupport.psql("SELECT count(*) FROM settlement.booking_sale WHERE schedule_id = ${schedule.id}") == listOf("3") &&
                BatchSupport.psql("SELECT count(*) FROM settlement.booking_refund WHERE booking_id = $refunded") == listOf("1")
        }
        return Sales(schedule, plain, discounted, refunded)
    }

    @Test
    fun `정산 Job은 정가 합과 환불과 할인 부담을 공연별로 집계한다`() = runTest {
        val sales = createSales()
        val performanceId = BatchSupport.psql(
            "SELECT DISTINCT performance_id FROM settlement.booking_sale WHERE schedule_id = ${sales.schedule.id}",
        ).single()

        val run = BatchSupport.runJob("dailySettlementJob", "targetDate=$today")

        assertEquals(0, run.exitCode, run.log.path)
        assertEquals("COMPLETED", run.status, run.log.path)
        val row = BatchSupport.psql(
            "SELECT sale_count, sale_amount, refund_count, refund_amount, net_amount, fee_rate, fee_amount, " +
                "payout_amount, platform_discount_amount " +
                "FROM settlement.daily_settlement WHERE settlement_date = '$today' AND performance_id = $performanceId",
        ).single().split("|")
        assertEquals("3", row[0])
        assertMoney(30000, row[1])
        assertEquals("1", row[2])
        assertMoney(10000, row[3])
        assertMoney(20000, row[4])
        assertEquals(0, BigDecimal("0.10").compareTo(BigDecimal(row[5])))
        assertMoney(2000, row[6])
        assertMoney(18000, row[7])
        assertMoney(DISCOUNT, row[8])
    }

    @Test
    fun `결제 대사 Job은 확정과 환불을 마친 주문에서 불일치를 만들지 않는다`() = runTest {
        val sales = createSales()
        val orders = listOf(sales.plain, sales.discounted, sales.refunded).joinToString(",")

        val run = BatchSupport.runJob("paymentReconciliationJob", "targetDate=$today", target = BatchTarget.PAYMENT)

        assertEquals(0, run.exitCode, run.log.path)
        assertEquals("COMPLETED", run.status, run.log.path)
        // PG(fake-pg)는 실행마다 비워지므로 우리 주문 3건이 스냅샷에 있어야 대사가 실제로 비교된 것이다
        assertEquals(
            listOf("3"),
            BatchSupport.paymentPsql(
                "SELECT count(*) FROM payment.pg_transaction_snapshot WHERE target_date = '$today' AND order_id IN ($orders)",
            ),
        )
        assertEquals(
            emptyList(),
            BatchSupport.paymentPsql(
                "SELECT order_id, mismatch_type FROM payment.reconciliation_mismatch WHERE target_date = '$today' AND order_id IN ($orders)",
            ),
        )
    }

    @Test
    fun `미결제 정리 Job은 만료된 PENDING 예매를 만료 처리하고 좌석을 돌려준다`() = runTest {
        val organizer = client.signUp("ORGANIZER")
        val schedule = client.createSchedule(organizer, 1)
        val buyer = client.signUp("USER")
        val booking = client.reserve(buyer, schedule, schedule.seatIds)
        assertEquals("PENDING", client.bookingDetail(buyer, booking)["status"].asString())
        // 보유 시간(5분)을 기다리지 않도록 만료 시각만 과거로 돌린다. 앱 코드에는 E2E 전용 경로가 없다
        assertEquals(
            listOf(booking.toString()),
            BatchSupport.psql(
                "UPDATE booking.booking SET expires_at = now() - interval '1 minute' WHERE id = $booking AND status = 'PENDING' RETURNING id",
            ),
        )

        val run = BatchSupport.runJob("expirePendingBookingsJob")

        assertEquals(0, run.exitCode, run.log.path)
        assertEquals("COMPLETED", run.status, run.log.path)
        assertEquals("EXPIRED", client.bookingDetail(buyer, booking)["status"].asString())
        // 선점이 풀렸으므로 다른 구매자가 같은 좌석을 예매할 수 있다
        val other: Member = client.signUp("USER")
        val rebooked = client.reserve(other, schedule, schedule.seatIds)
        assertTrue(rebooked != booking)
    }

    private fun assertMoney(expected: Long, actual: String) =
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "기대 $expected, 실제 $actual")

    private companion object {
        const val DISCOUNT = 3000L
    }
}
