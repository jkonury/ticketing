package io.github.ticketing.fakepg.payment.service

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

enum class HoldState { WAITING, HELD }

data class HoldRegistration(val state: HoldState, val created: Boolean)

/**
 * 주문 단위로 승인 웹훅을 보류했다가 명시적으로 풀어 주는 테스트 제어(`fake-pg.test-controls.enabled=true`일 때만 등록한다).
 * 고정 지연에 기대지 않고 "다른 상태를 만든 뒤 승인이 도착한다"는 순서를 결정적으로 만든다.
 *
 * 주문마다 상태 하나(등록됨 → 보류됨(거래) → 해제하면 사라짐)만 두고 모든 전이를 같은 락 안에서 적용한다.
 * 결제 요청 전에 등록하면 그 주문의 첫 승인(SUCCESS 시나리오)만 보류한다. 환불 웹훅은 영향이 없다.
 * 등록 상태가 [MAX_ENTRIES]개에 이르면 새 주문의 등록을 거절해 무한히 쌓이지 않게 한다.
 */
@Component
@ConditionalOnProperty(prefix = "fake-pg.test-controls", name = ["enabled"], havingValue = "true")
class ApprovalHolds {
    private sealed interface Hold {
        data object Registered : Hold
        data class Held(val transaction: Transaction) : Hold
    }

    private val holds = HashMap<String, Hold>()

    /** 주문의 현재 상태를 돌려준다. 이미 등록·보류 중이면 그대로이고(created=false), 상한 때문에 등록할 수 없으면 null. */
    @Synchronized
    fun register(orderId: String): HoldRegistration? {
        val current = holds[orderId]
        return when {
            current != null -> HoldRegistration(current.state(), created = false)

            holds.size >= MAX_ENTRIES -> null

            else -> {
                holds[orderId] = Hold.Registered
                HoldRegistration(HoldState.WAITING, created = true)
            }
        }
    }

    /** 등록된 주문의 거래면 보류하고 true를 돌려준다. 이미 보류 중인 주문의 다른 거래는 보류하지 않고 기존 보류를 유지한다. */
    @Synchronized
    fun holdIfRegistered(tx: Transaction): Boolean {
        if (holds[tx.orderId] != Hold.Registered) return false
        holds[tx.orderId] = Hold.Held(tx)
        return true
    }

    /** 보류된 거래를 한 번만 꺼내며 등록도 남기지 않는다. 보류된 거래가 없으면 null. */
    @Synchronized
    fun release(orderId: String): Transaction? {
        val current = holds[orderId] as? Hold.Held ?: return null
        holds.remove(orderId)
        return current.transaction
    }

    @Synchronized
    fun state(orderId: String): HoldState? = holds[orderId]?.state()

    private fun Hold.state() = if (this is Hold.Held) HoldState.HELD else HoldState.WAITING

    companion object {
        const val MAX_ENTRIES = 1_000
    }
}
