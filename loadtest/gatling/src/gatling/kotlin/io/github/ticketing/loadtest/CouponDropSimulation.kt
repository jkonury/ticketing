package io.github.ticketing.loadtest

import io.gatling.javaapi.core.CoreDsl.bodyString
import io.gatling.javaapi.core.CoreDsl.details
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.pause
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.time.Duration

private const val COUPON_DROP_VUS = 10000
private const val CLAIM_P95_MS = 1000
private const val CLAIM_P95_PERCENTILE = 95.0
private const val PRECONNECT_LATE = "coupon_preconnect_late"
private const val STATUS_OK_MIN = 200
private const val STATUS_OK_MAX = 299
private const val STATUS_CONFLICT = 409
private const val STATUS_RATE_LIMITED = 429
private val COUPON_DROP_START_DELAY = Duration.ofSeconds(40)
private val COUPON_DROP_PRECONNECT_RAMP = Duration.ofSeconds(20)
private val COUPON_DROP_MAX_DURATION = Duration.ofMinutes(10)
private val EXPECTED_CLAIM_STATUSES: List<Int> = (STATUS_OK_MIN..STATUS_OK_MAX).toList() + STATUS_CONFLICT + STATUS_RATE_LIMITED
private val PRECONNECT_OK_STATUSES: List<Int> = (STATUS_OK_MIN..STATUS_OK_MAX).toList()

/** 시나리오 C: 많은 회원이 같은 시각에 같은 선착순(LIMITED) 쿠폰을 한 번씩 받는다(k6 coupon-drop.ts 대응). */
class CouponDropSimulation : Simulation() {
    @Volatile private var startAt = 0L
    private val vusCount = LoadEnv.vus(COUPON_DROP_VUS)
    private val coupon = LoadEnv.readJson("COUPON_FILE")
    private val definitionId = coupon.path("definitionId").asLong()
    private val totalQuantity = coupon.path("totalQuantity").asLong()

    override fun before() {
        startAt = System.currentTimeMillis() + COUPON_DROP_START_DELAY.toMillis()
    }

    init {
        val members = LoadEnv.readJson("MEMBERS_FILE")
        val userIdMin = members.path("userIdMin").asLong()
        val userIdMax = members.path("userIdMax").asLong()
        val claim =
            scenario("coupon_drop")
                .exec { session ->
                    val virtualUser = LoadData.nextVirtualUser()
                    val memberId = userIdMin + virtualUser - 1
                    check(memberId <= userIdMax) { "VU $virtualUser 에 배정할 회원이 없습니다(회원 ID $userIdMin~$userIdMax)." }
                    session.set(Keys.TOKEN, Jwt.signAccessToken(memberId, "USER"))
                }
                // 받기 시각에 새 TCP 연결 1만 개가 몰리면 listen 큐가 넘치므로, 미리 연결을 열어 두고 keep-alive로 받는다
                .exec(
                    http("coupon_preconnect")
                        .get("/api/v1/coupons")
                        .header("Authorization", "Bearer #{${Keys.TOKEN}}")
                        .check(status().`in`(PRECONNECT_OK_STATUSES)),
                ).exec { session ->
                    if (System.currentTimeMillis() > startAt) LoadMetrics.count(PRECONNECT_LATE)
                    session
                }.exec(pause { _ -> Duration.ofMillis((startAt - System.currentTimeMillis()).coerceAtLeast(0)) })
                // 429도 다시 보내지 않고 1번만 시도한다(k6와 같다)
                .exec(
                    http("coupon_claim")
                        .post("/api/v1/coupons/$definitionId/claims")
                        .header("Authorization", "Bearer #{${Keys.TOKEN}}")
                        .transformResponse { response, _ ->
                            LoadMetrics.record(LoadMetrics.CLAIM_LATENCY, response.endTimestamp() - response.startTimestamp())
                            response
                        }.check(
                            status().saveAs(Keys.LAST_STATUS),
                            bodyString().saveAs(Keys.RESPONSE_BODY),
                            status().`in`(EXPECTED_CLAIM_STATUSES),
                        ),
                ).exec(
                    exec { session ->
                        // 응답이 없으면(네트워크 오류) 상태가 저장되지 않아 other로 센다
                        LoadMetrics.count(classify(Api.statusOf(session), session.getString(Keys.RESPONSE_BODY).orEmpty()))
                        session
                    },
                )

        setUp(claim.injectOpen(rampUsers(vusCount).during(COUPON_DROP_PRECONNECT_RAMP)))
            .protocols(Api.protocol)
            .maxDuration(COUPON_DROP_MAX_DURATION)
            .assertions(
                details("coupon_claim").failedRequests().count().lt(1L),
                details("coupon_claim").responseTime().percentile(CLAIM_P95_PERCENTILE).lt(CLAIM_P95_MS),
            )
    }

    private fun classify(status: Int, body: String): String = when {
        status in STATUS_OK_MIN..STATUS_OK_MAX -> LoadMetrics.CLAIM_CREATED
        status == STATUS_CONFLICT && body.contains("COUPON_ALREADY_CLAIMED") -> LoadMetrics.CLAIM_ALREADY_CLAIMED
        status == STATUS_CONFLICT && body.contains("COUPON_SOLD_OUT") -> LoadMetrics.CLAIM_SOLD_OUT
        status == STATUS_RATE_LIMITED -> LoadMetrics.CLAIM_RATE_LIMITED
        else -> LoadMetrics.CLAIM_OTHER
    }

    override fun after() {
        // 발급(201)이 총수량을 넘으면 초과 발급이다. 최종 판정은 verify-coupon-integrity.sh가 한다.
        listOf(
            LoadMetrics.CLAIM_CREATED,
            LoadMetrics.CLAIM_ALREADY_CLAIMED,
            LoadMetrics.CLAIM_SOLD_OUT,
            LoadMetrics.CLAIM_RATE_LIMITED,
            LoadMetrics.CLAIM_OTHER,
            PRECONNECT_LATE,
        ).forEach { LoadMetrics.count(it, 0) }
        LoadMetrics.finish()
        val late = LoadMetrics.value(PRECONNECT_LATE)
        print("사전 연결이 받기 시각 이후에 끝난 사용자: ${late}명\n")
        val issued = LoadMetrics.value(LoadMetrics.CLAIM_CREATED)
        if (issued > totalQuantity) print("경고: 발급 응답 ${issued}건이 총수량 ${totalQuantity}장을 넘었습니다.\n")
    }
}
