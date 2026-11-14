package io.github.ticketing.loadtest

import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.atOnceUsers
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.pause
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Session
import io.gatling.javaapi.core.Simulation
import java.time.Duration

private const val PAIR_COUNT = 100
private const val POLL_ATTEMPTS = 30
private val MAX_DURATION = Duration.ofMinutes(10)
private val SECOND_START_DELAY = Duration.ofSeconds(45)

private object ConflictKeys {
    const val PAIR = "pair"
    const val ROLE = "role"
    const val MEMBER_ID = "memberId"
    const val CREATED_ON_TIME = "createdOnTime"
    const val PAY_STATUS = "payStatus"
    const val REPAY_STATUS = "repayStatus"
    const val POLLING = "polling"
    const val POLL_NO = "pollNo"
    const val FINAL_STATUS = "finalStatus"
}

/**
 * 좌석 충돌 환불 보상 검증(ADR 0024 확장). 선점 키 유실을 인위적으로 주입한 정합성 검증이며 정상 A·B 성능 비교와 별도로 해석한다.
 * 두 단계로 실행한다. runner가 첫 단계 뒤 첫 예매의 겹치는 좌석 선점 키만 지운다.
 *   PHASE=first : 그룹마다 첫 회원(A)이 대기열 → 입장 → 좌석 A로 PENDING 예매를 만든다(100 VU).
 *   PHASE=second: 둘째 회원(B)이 대기열 → 입장 → 겹치는 좌석으로 예매하고, 같은 시각에 A·B 모두 결제를 요청한다(200 VU).
 * 결제 요청은 같은 멱등 키로 한 번 더 보내 추가 확정·환불이 생기지 않는지 확인한다. 어느 쪽이 확정될지는 고정하지 않는다.
 */
class SeatConflictRefundSimulation : Simulation() {
    private val phase = System.getenv("PHASE")
    private val vusCount = if (phase == "first") PAIR_COUNT else PAIR_COUNT * 2

    @Volatile private var startAt = 0L

    init {
        require(phase == "first" || phase == "second") { "PHASE는 first 또는 second여야 합니다." }
        val flow = scenario("seat_conflict_refund").exec(if (phase == "first") runFirst() else runSecond())

        setUp(flow.injectOpen(atOnceUsers(vusCount)))
            .protocols(Api.protocol)
            .maxDuration(MAX_DURATION)
            .assertions(commonAssertions(vusCount, listOf("sse_queue")))
    }

    override fun before() {
        startAt = System.currentTimeMillis() + SECOND_START_DELAY.toMillis()
    }

    override fun after() {
        LoadMetrics.finish()
        LoadEnv.writeReadiness(mapOf("phase" to phase, "pairs" to PAIR_COUNT) + LoadMetrics.readinessReport(PAIR_COUNT, startAt))
    }

    private fun initialize(): ChainBuilder = exec { session ->
        val virtualUser = LoadData.nextVirtualUser()
        val role = if (phase == "first" || virtualUser <= PAIR_COUNT) "A" else "B"
        val pair = if (virtualUser <= PAIR_COUNT) virtualUser.toInt() else (virtualUser - PAIR_COUNT).toInt()
        val memberId = LoadData.memberIdFor(virtualUser)
        session
            .set(Keys.VIRTUAL_USER, virtualUser)
            .set(ConflictKeys.PAIR, pair)
            .set(ConflictKeys.ROLE, role)
            .set(ConflictKeys.MEMBER_ID, memberId)
            .set(Keys.TOKEN, Jwt.signAccessToken(memberId, "USER"))
            .set(Keys.SCHEDULE_ID, LoadData.scheduleId)
    }

    private fun enterAndAdmit(): ChainBuilder = Endpoints
        .enterQueue()
        .exitHereIf { session -> !Api.isOk(session) }
        .exec { session ->
            LoadMetrics.count(LoadMetrics.FLOW_REGISTERED)
            session
        }.exec(QueueStreams.waitForAdmission())
        .exitHereIf { session -> !session.getBoolean(Keys.FLOW_OK) }
        .exec { session ->
            LoadMetrics.count(LoadMetrics.FLOW_ADMITTED)
            session
        }

    private fun createBookingFor(seats: (Int) -> List<Int>): ChainBuilder = exec { session ->
        LoadMetrics.count(LoadMetrics.FLOW_BOOKING_ATTEMPT)
        session
            .set(Keys.SEAT_IDS, seats(session.getInt(ConflictKeys.PAIR)))
            .set(Keys.BOOKING_KEY, Api.newIdempotencyKey("gatlingc", session.getLong(Keys.VIRTUAL_USER)))
    }.exec(Endpoints.createBooking())
        .exitHereIf { session -> !Api.isOk(session) || !session.contains(Keys.BOOKING_ID) }
        .exec { session ->
            LoadMetrics.count(LoadMetrics.FLOW_BOOKING_CREATED)
            session
        }

    private fun runFirst(): ChainBuilder = initialize()
        .exec(enterAndAdmit())
        .exec(createBookingFor { pair -> LoadData.pairAt(pair).seatsA })
        .exec { session ->
            println(
                "PAIR_FIRST " +
                    """{"pair":${session.getInt(
                        ConflictKeys.PAIR,
                    )},"memberId":${session.getLong(ConflictKeys.MEMBER_ID)},"bookingId":${session.getLong(Keys.BOOKING_ID)}}""",
            )
            session
        }

    private fun runSecond(): ChainBuilder = initialize()
        .doIfOrElse { session -> session.getString(ConflictKeys.ROLE) == "A" }
        .then(
            exec { session ->
                session.set(
                    Keys.BOOKING_ID,
                    LoadData.firstBookingOf(session.getInt(ConflictKeys.PAIR)).bookingId,
                ).set(ConflictKeys.CREATED_ON_TIME, true)
            },
        ).orElse(
            exec(enterAndAdmit())
                .exec { session ->
                    // 정시 준비 여부. 시작 시각 뒤에 예매가 만들어졌으면 지각이다
                    LoadMetrics.count(
                        if (System.currentTimeMillis() <=
                            startAt
                        ) {
                            LoadMetrics.FLOW_READY_ON_TIME
                        } else {
                            LoadMetrics.FLOW_READY_LATE
                        },
                    )
                    session
                }.exec(createBookingFor { pair -> LoadData.pairAt(pair).seatsB })
                .exec { session -> session.set(ConflictKeys.CREATED_ON_TIME, System.currentTimeMillis() <= startAt) },
        ).exec(pause { _ -> Duration.ofMillis((startAt - System.currentTimeMillis()).coerceAtLeast(0)) })
        .exec(payAndSettle())

    private fun payAndSettle(): ChainBuilder = exec { session -> session }
        .exec { session -> session.set(Keys.PAY_KEY, Api.newIdempotencyKey("gatlingc", session.getLong(Keys.VIRTUAL_USER))) }
        .exec(Endpoints.payBooking())
        .exec { session -> session.set(ConflictKeys.PAY_STATUS, Api.statusOf(session)) }
        .doIf { session -> !Api.isOk(session) }
        .then(
            exec { session ->
                println("PAIR_SECOND ${resultJson(session, includeFinal = false)}")
                session
            },
        ).exitHereIf { session -> !Api.isOk(session.getInt(ConflictKeys.PAY_STATUS)) }
        .exec { session ->
            LoadMetrics.count(LoadMetrics.CONFLICT_PAY_ACCEPTED)
            LoadMetrics.count(LoadMetrics.FLOW_PAYMENT_REQUESTED)
            session
        }
        // 같은 멱등 키로 한 번 더 보낸다
        .exec(Endpoints.payBooking())
        .exec { session ->
            val status = Api.statusOf(session)
            LoadMetrics.count(if (Api.isOk(status)) LoadMetrics.CONFLICT_REPAY_ACCEPTED else LoadMetrics.CONFLICT_REPAY_REJECTED)
            session.set(
                ConflictKeys.REPAY_STATUS,
                status,
            ).set(ConflictKeys.FINAL_STATUS, "PENDING").set(ConflictKeys.POLLING, true).set(ConflictKeys.POLL_NO, 0)
        }.asLongAs { session -> session.getBoolean(ConflictKeys.POLLING) }
        .on(
            pause(1)
                .exec(Endpoints.bookingDetail())
                .exec { session -> pollFinalStatus(session) },
        ).exec { session ->
            LoadMetrics.count(
                when (session.getString(ConflictKeys.FINAL_STATUS)) {
                    "CONFIRMED" -> LoadMetrics.CONFLICT_FINAL_CONFIRMED
                    "CANCELLED" -> LoadMetrics.CONFLICT_FINAL_CANCELLED
                    else -> LoadMetrics.CONFLICT_FINAL_OTHER
                },
            )
            println("PAIR_SECOND ${resultJson(session, includeFinal = true)}")
            session
        }

    private fun pollFinalStatus(session: Session): Session {
        val pollNo = session.getInt(ConflictKeys.POLL_NO) + 1
        val status = if (Api.isOk(session)) session.getString(Keys.DETAIL_STATUS) else null
        val settled = status != null && status != "PENDING"
        return session
            .set(ConflictKeys.FINAL_STATUS, status ?: session.getString(ConflictKeys.FINAL_STATUS))
            .set(ConflictKeys.POLL_NO, pollNo)
            .set(ConflictKeys.POLLING, !settled && pollNo < POLL_ATTEMPTS)
    }

    private fun resultJson(session: Session, includeFinal: Boolean): String {
        val base =
            """"pair":${session.getInt(ConflictKeys.PAIR)},"role":"${session.getString(ConflictKeys.ROLE)}",""" +
                """"memberId":${session.getLong(ConflictKeys.MEMBER_ID)},"bookingId":${session.getLong(Keys.BOOKING_ID)}"""
        if (!includeFinal) return "{$base,\"payStatus\":${session.getInt(ConflictKeys.PAY_STATUS)}}"
        return "{$base,\"createdOnTime\":${session.getBoolean(ConflictKeys.CREATED_ON_TIME)}," +
            "\"payStatus\":${session.getInt(ConflictKeys.PAY_STATUS)},\"repayStatus\":${session.getInt(ConflictKeys.REPAY_STATUS)}," +
            "\"finalStatus\":\"${session.getString(ConflictKeys.FINAL_STATUS)}\"}"
    }
}
