package io.github.ticketing.fakepg.payment

import io.github.ticketing.fakepg.payment.service.ApprovalHolds
import io.github.ticketing.fakepg.payment.service.HoldState
import io.github.ticketing.fakepg.payment.service.Scenario
import io.github.ticketing.fakepg.payment.service.Transaction
import io.github.ticketing.fakepg.payment.service.TransactionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ApprovalHoldsTest {
    private val holds = ApprovalHolds()

    private fun transaction(orderId: String, id: String) =
        Transaction(id, orderId, 1000, Scenario.SUCCESS, TransactionStatus.PENDING, Instant.EPOCH)

    @Test
    fun `같은 주문을 다시 등록해도 상태는 그대로이고 새로 등록한 것으로 보지 않는다`() {
        val first = holds.register("order-1")
        val second = holds.register("order-1")

        assertThat(first).isNotNull
        assertThat(first!!.created).isTrue()
        assertThat(second!!.created).isFalse()
        assertThat(second.state).isEqualTo(HoldState.WAITING)
    }

    @Test
    fun `보류 중에 재등록하면 현재 상태를 돌려주고 보류 거래를 유지한다`() {
        holds.register("order-1")
        assertThat(holds.holdIfRegistered(transaction("order-1", "txn-1"))).isTrue()

        val again = holds.register("order-1")

        assertThat(again!!.state).isEqualTo(HoldState.HELD)
        assertThat(again.created).isFalse()
        assertThat(holds.state("order-1")).isEqualTo(HoldState.HELD)
        assertThat(holds.release("order-1")?.transactionId).isEqualTo("txn-1")
    }

    @Test
    fun `같은 주문의 다른 거래는 보류 중인 거래를 덮어쓰지 않고 보류되지도 않는다`() {
        holds.register("order-1")
        holds.holdIfRegistered(transaction("order-1", "txn-1"))
        holds.register("order-1")

        val otherHeld = holds.holdIfRegistered(transaction("order-1", "txn-2"))

        assertThat(otherHeld).isFalse()
        assertThat(holds.release("order-1")?.transactionId).isEqualTo("txn-1")
    }

    @Test
    fun `해제하면 등록이 남지 않아 이후 거래는 보류되지 않는다`() {
        holds.register("order-1")
        holds.holdIfRegistered(transaction("order-1", "txn-1"))

        holds.release("order-1")

        assertThat(holds.state("order-1")).isNull()
        assertThat(holds.holdIfRegistered(transaction("order-1", "txn-2"))).isFalse()
        assertThat(holds.release("order-1")).isNull()
    }

    @Test
    fun `보류된 거래가 없으면 등록만 된 주문도 해제되지 않고 등록은 유지된다`() {
        holds.register("order-1")

        assertThat(holds.release("order-1")).isNull()
        assertThat(holds.state("order-1")).isEqualTo(HoldState.WAITING)
    }

    @Test
    fun `동시에 해제해도 보류된 거래는 정확히 한 번만 풀린다`() {
        holds.register("order-1")
        holds.holdIfRegistered(transaction("order-1", "txn-1"))
        val threads = 16
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val released = AtomicInteger()

        Executors.newFixedThreadPool(threads).use { pool ->
            val futures = (1..threads).map {
                pool.submit {
                    ready.countDown()
                    start.await()
                    if (holds.release("order-1") != null) released.incrementAndGet()
                }
            }
            ready.await()
            start.countDown()
            futures.forEach { it.get() }
        }

        assertThat(released.get()).isEqualTo(1)
    }

    @Test
    fun `등록 상태가 상한에 이르면 새 주문은 거절하고 기존 주문은 그대로 조회된다`() {
        repeat(ApprovalHolds.MAX_ENTRIES) { assertThat(holds.register("order-$it")).isNotNull }

        assertThat(holds.register("order-overflow")).isNull()
        assertThat(holds.register("order-0")!!.created).isFalse()
        holds.holdIfRegistered(transaction("order-0", "txn-0"))
        holds.release("order-0")
        assertThat(holds.register("order-overflow")).isNotNull
    }
}
