package io.github.ticketing.loadtest

import io.gatling.javaapi.core.Assertion
import io.gatling.javaapi.core.CoreDsl.atOnceUsers
import io.gatling.javaapi.core.CoreDsl.details
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import java.time.Duration

// 입장 배치 규모(100명 이하)는 p95 300ms, 1,000명 동시 도착은 CPU 포화 스트레스 조건이라 1,500ms로 판정한다.
// 1,000명 300ms는 후속 튜닝 목표다(docs/performance/m5-improvements.md). 기준 미달도 기록하므로 중단 조건은 두지 않는다.
private const val STRESS_VUS = 100
private const val BOOKING_P95_MS = 300
private const val STRESS_BOOKING_P95_MS = 1500
private const val HOT_SEAT_COUNT = 100
private const val FULL_PERCENT = 100.0
private const val GOOD_SSE_SUCCESS_PERCENT = 99.9
private const val BOOKING_P95_PERCENTILE = 95.0
private const val QUEUE_SURGE_VUS = 10000
private const val HOT_SEATS_VUS = 1000
private val QUEUE_SURGE_MAX_DURATION = Duration.ofMinutes(15)
private val HOT_SEATS_MAX_DURATION = Duration.ofMinutes(10)
private val HOT_SEATS_START_DELAY = Duration.ofSeconds(60)

internal fun commonAssertions(vusCount: Int, streams: List<String>): List<Assertion> {
    val limit = LoadEnv.bookingP95Limit(if (vusCount > STRESS_VUS) STRESS_BOOKING_P95_MS else BOOKING_P95_MS)
    return listOf(
        details("booking_create").responseTime().percentile(BOOKING_P95_PERCENTILE).lt(limit),
        global().failedRequests().percent().lt(1.0),
    ) + streams.map { details(it).successfulRequests().percent().gt(GOOD_SSE_SUCCESS_PERCENT) }
}

/** 시나리오 A: 대기열에 한꺼번에 몰린 사용자가 입장해 좌석을 선점·결제한다(ADR 0024). */
class QueueSurgeSimulation : Simulation() {
    init {
        val vusCount = LoadEnv.vus(QUEUE_SURGE_VUS)
        val flow = scenario("queue_surge").exec(BookingFlow.chain(FlowConfig(fixedCandidates = null, conflictRetries = 2)))

        setUp(flow.injectOpen(atOnceUsers(vusCount)))
            .protocols(Api.protocol)
            .maxDuration(QUEUE_SURGE_MAX_DURATION)
            .assertions(commonAssertions(vusCount, listOf("sse_queue", "sse_seats")))
    }

    override fun after() {
        LoadMetrics.finish()
    }
}

/** 시나리오 B: 입장한 사용자가 같은 시각에 앞줄 100석을 놓고 경합한다(ADR 0024). */
class HotSeatsSimulation : Simulation() {
    @Volatile private var startAt = 0L
    private val vusCount = LoadEnv.vus(HOT_SEATS_VUS)

    // 모든 사용자가 입장할 시간을 주고 같은 시각에 시작한다
    override fun before() {
        startAt = System.currentTimeMillis() + HOT_SEATS_START_DELAY.toMillis()
    }

    init {
        val flow =
            scenario("hot_seats").exec(
                BookingFlow.chain(FlowConfig(fixedCandidates = LoadData.hotSeatIds, conflictRetries = 0, startAt = { startAt })),
            )

        setUp(flow.injectOpen(atOnceUsers(vusCount)))
            .protocols(Api.protocol)
            .maxDuration(HOT_SEATS_MAX_DURATION)
            .assertions(commonAssertions(vusCount, listOf("sse_queue")))
    }

    override fun after() {
        // 확정 좌석이 핫 좌석 수를 넘으면 중복 판매다. 최종 판정은 verify-integrity.sh가 한다.
        LoadMetrics.finish()
        val seats = LoadMetrics.value(LoadMetrics.SEATS_CONFIRMED)
        if (seats > HOT_SEAT_COUNT) print("경고: 확정 좌석 ${seats}석이 핫 좌석 ${HOT_SEAT_COUNT}석을 넘었습니다. verify-integrity.sh로 중복 판매를 확인하세요.\n")
        val report = LoadMetrics.readinessReport(vusCount, startAt)
        print(
            "준비 현황: 정시 준비 ${report["readyOnTime"]}/$vusCount, 지각 ${report["readyLate"]}, 예매 시도 ${report["bookingAttempt"]}, 1,000명 동시 경합 조건 ${if (report["fullContention"] == true) "충족" else "미충족"}\n",
        )
        LoadEnv.writeReadiness(report)
    }
}

/** Gateway 연결과 공개 API를 확인하는 최소 시나리오. */
class SmokeSimulation : Simulation() {
    init {
        val smoke =
            scenario("smoke").exec(
                io.gatling.javaapi.http.HttpDsl
                    .http("GET /api/v1/performances")
                    .get("/api/v1/performances")
                    .check(io.gatling.javaapi.http.HttpDsl.status().`is`(200)),
            )
        setUp(
            smoke.injectOpen(atOnceUsers(1)),
        ).protocols(Api.protocol).assertions(global().successfulRequests().percent().`is`(FULL_PERCENT))
    }
}
