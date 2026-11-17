package io.github.ticketing.loadtest

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Gatling은 사용자 정의 카운터가 없어 k6의 Counter·Trend를 메모리 집계로 대신하고, 종료 시 [summary]로 출력한다.
 * HTTP·SSE 요청 지표(응답 시간, 성공/실패)는 Gatling 리포트와 assertion이 맡는다.
 */
private const val P95 = 95
private const val PERCENT_BASE = 100

object LoadMetrics {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val trends = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    fun count(name: String, amount: Long = 1) {
        counters.computeIfAbsent(name) { AtomicLong() }.addAndGet(amount)
    }

    fun record(name: String, value: Long) {
        trends.computeIfAbsent(name) { ConcurrentLinkedQueue() }.add(value)
    }

    /** 종료 시 한 번 호출한다. 요약을 출력하고 SUMMARY_FILE이 있으면 파일로도 남긴다. */
    fun finish() {
        print(summary())
        LoadEnv.writeSummary(counters.mapValues { (_, counter) -> counter.get() }, bookingOutcomes())
    }

    fun recordBooking(status: Int, code: String?, durationMs: Long) {
        val outcome = when {
            Api.isOk(status) -> "success"
            status == 409 && code == "SEAT_ALREADY_HELD" -> "conflict"
            status == 429 -> "rateLimited"
            else -> "other"
        }
        record("booking_$outcome", durationMs)
    }

    private fun bookingOutcomes(): Map<String, Any> = listOf("success", "conflict", "rateLimited", "other").associateWith { outcome ->
        val sorted = trends["booking_$outcome"]?.sorted().orEmpty()
        mapOf(
            "count" to sorted.size,
            "p50" to sorted.takeIf { it.isNotEmpty() }?.let { percentile(it, 50) },
            "p95" to sorted.takeIf { it.isNotEmpty() }?.let { percentile(it, 95) },
            "p99" to sorted.takeIf { it.isNotEmpty() }?.let { percentile(it, 99) },
        )
    }

    fun value(name: String): Long = counters[name]?.get() ?: 0

    fun summary(): String {
        val lines = mutableListOf("=== 요약(사용자 정의 지표) ===")
        counters.toSortedMap().forEach { (name, counter) -> lines += "$name: count=${counter.get()}" }
        trends.toSortedMap().forEach { (name, samples) ->
            val sorted = samples.sorted()
            if (sorted.isNotEmpty()) {
                lines +=
                    "$name: count=${sorted.size} avg=${sorted.average().toLong()} p(95)=${percentile(sorted, P95)} max=${sorted.last()}"
            }
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun percentile(sorted: List<Long>, percent: Int): Long =
        sorted[((sorted.size * percent + PERCENT_BASE - 1) / PERCENT_BASE - 1).coerceIn(0, sorted.lastIndex)]

    // 지표 이름. k6 시나리오와 같은 이름을 쓴다
    const val QUEUE_WAIT = "queue_wait"
    const val CONFIRM_LATENCY = "confirm_latency"
    const val BOOKING_CONFLICT = "booking_conflict"
    const val BOOKING_CONFIRMED = "booking_confirmed"
    const val BOOKING_NOT_CONFIRMED = "booking_not_confirmed"
    const val SEATS_CONFIRMED = "seats_confirmed"
    const val SOLD_OUT_EXITS = "sold_out_exits"
    const val UNEXPECTED_ERRORS = "unexpected_errors"
    const val QUEUE_STREAM_REMOVED = "queue_stream_removed"
    const val QUEUE_STREAM_TICKET_FAILED = "queue_stream_ticket_failed"
    const val QUEUE_STREAM_CLOSED_WITHOUT_RESULT = "queue_stream_closed_without_result"
    const val FLOW_REGISTERED = "flow_registered"
    const val FLOW_ADMITTED = "flow_admitted"
    const val FLOW_READY_ON_TIME = "flow_ready_on_time"
    const val FLOW_READY_LATE = "flow_ready_late"
    const val FLOW_BOOKING_ATTEMPT = "flow_booking_attempt"
    const val FLOW_BOOKING_CREATED = "flow_booking_created"
    const val FLOW_PAYMENT_REQUESTED = "flow_payment_requested"
    const val CONFLICT_PAY_ACCEPTED = "conflict_pay_accepted"
    const val CONFLICT_REPAY_ACCEPTED = "conflict_repay_accepted"
    const val CONFLICT_REPAY_REJECTED = "conflict_repay_rejected"
    const val CONFLICT_FINAL_CONFIRMED = "conflict_final_confirmed"
    const val CONFLICT_FINAL_CANCELLED = "conflict_final_cancelled"
    const val CONFLICT_FINAL_OTHER = "conflict_final_other"
    const val CLAIM_CREATED = "claim_created"
    const val CLAIM_ALREADY_CLAIMED = "claim_already_claimed"
    const val CLAIM_SOLD_OUT = "claim_sold_out"
    const val CLAIM_RATE_LIMITED = "claim_rate_limited"
    const val CLAIM_OTHER = "claim_other"
    const val CLAIM_LATENCY = "claim_latency"

    /** 시나리오 B의 준비·시작 현황. */
    fun readinessReport(vusCount: Int, startAtEpochMs: Long?): Map<String, Any?> {
        val readyOnTime = value(FLOW_READY_ON_TIME)
        val readyLate = value(FLOW_READY_LATE)
        return linkedMapOf(
            "vus" to vusCount,
            "startAtEpochMs" to startAtEpochMs,
            "startAtIso" to startAtEpochMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
            "registered" to value(FLOW_REGISTERED),
            "admitted" to value(FLOW_ADMITTED),
            "readyOnTime" to readyOnTime,
            "readyLate" to readyLate,
            "bookingAttempt" to value(FLOW_BOOKING_ATTEMPT),
            // 정시 준비 인원이 VU 수에 못 미치는 사용자(등록·입장 실패 또는 지각). 조용히 제외하지 않고 그대로 남긴다.
            "notReadyOnTime" to vusCount - readyOnTime,
            "fullContention" to (readyOnTime == vusCount.toLong() && readyLate == 0L),
        )
    }
}
