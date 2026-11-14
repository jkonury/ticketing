package io.github.ticketing.loadtest

import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.pause
import io.gatling.javaapi.core.Session
import java.time.Duration

private const val MAX_SEATS_PER_BOOKING = 4
private const val CONFIRM_POLL_ATTEMPTS = 30

private object FlowKeys {
    const val TRY_AGAIN = "tryAgain"
    const val ATTEMPT_NO = "attemptNo"
    const val ATTEMPTED = "attempted"
    const val CREATED = "created"
    const val POLLING = "polling"
    const val POLL_NO = "pollNo"
    const val PAID_AT = "paidAt"
}

class FlowConfig(
    // 있으면 이 좌석에서만 고르고 좌석 스트림은 읽지 않는다(시나리오 B). 없으면 스냅샷의 AVAILABLE 좌석에서 고른다.
    val fixedCandidates: List<Int>?,
    // 409 SEAT_ALREADY_HELD 뒤 다시 시도하는 횟수
    val conflictRetries: Int,
    // epoch ms. 입장 뒤 이 시각까지 기다렸다가 예매를 시작한다. 시작 시각은 시뮬레이션 시작 후에 정해지므로 함수로 받는다
    val startAt: (() -> Long)? = null,
)

/** 대기열 → 입장 → 좌석 선택 → 예매 → 결제 → 확정 폴링. 실패하면 조용히 그 사용자의 흐름만 끝낸다. */
object BookingFlow {
    fun chain(config: FlowConfig): ChainBuilder = exec { session ->
        val virtualUser = LoadData.nextVirtualUser()
        session
            .set(Keys.VIRTUAL_USER, virtualUser)
            .set(Keys.TOKEN, Jwt.signAccessToken(LoadData.memberIdFor(virtualUser), "USER"))
            .set(Keys.SCHEDULE_ID, LoadData.scheduleId)
            .set(Keys.ENTERED_AT, System.currentTimeMillis())
    }.exec(Endpoints.enterQueue())
        .exitHereIf { session -> !Api.isOk(session) }
        .exec { session ->
            LoadMetrics.count(LoadMetrics.FLOW_REGISTERED)
            session
        }.exec(QueueStreams.waitForAdmission())
        .exitHereIf { session -> !session.getBoolean(Keys.FLOW_OK) }
        .exec { session ->
            LoadMetrics.count(LoadMetrics.FLOW_ADMITTED)
            config.startAt?.let { startAt ->
                LoadMetrics.count(
                    if (System.currentTimeMillis() <=
                        startAt()
                    ) {
                        LoadMetrics.FLOW_READY_ON_TIME
                    } else {
                        LoadMetrics.FLOW_READY_LATE
                    },
                )
            }
            LoadMetrics.record(LoadMetrics.QUEUE_WAIT, System.currentTimeMillis() - session.getLong(Keys.ENTERED_AT))
            session
        }.exec(waitUntilStart(config))
        .exec(createBookingLoop(config))
        .exitHereIf { session -> !session.getBoolean(FlowKeys.CREATED) }
        .exec { session -> session.set(Keys.PAY_KEY, Api.newIdempotencyKey("gatling", session.getLong(Keys.VIRTUAL_USER))) }
        .exec(Endpoints.payBooking())
        .exitHereIf { session -> !Api.isOk(session) }
        .exec { session ->
            LoadMetrics.count(LoadMetrics.FLOW_PAYMENT_REQUESTED)
            session
        }.exec(awaitConfirmation())

    private fun waitUntilStart(config: FlowConfig): ChainBuilder {
        val startAt = config.startAt ?: return exec { session -> session }
        return pause { _ -> Duration.ofMillis((startAt() - System.currentTimeMillis()).coerceAtLeast(0)) }
    }

    private fun createBookingLoop(config: FlowConfig): ChainBuilder {
        val fixed = config.fixedCandidates
        val selectCandidates =
            if (fixed != null) {
                exec { session -> session.set(Keys.AVAILABLE_SEATS, fixed) }
            } else {
                QueueStreams.readSeatSnapshot().exec { session ->
                    val soldOut = session.getBoolean(Keys.FLOW_OK) && availableSeats(session).isEmpty()
                    if (soldOut) {
                        LoadMetrics.count(LoadMetrics.SOLD_OUT_EXITS)
                        session.set(Keys.FLOW_OK, false)
                    } else {
                        session
                    }
                }
            }
        return exec { session ->
            session.set(FlowKeys.TRY_AGAIN, true).set(FlowKeys.ATTEMPT_NO, 0).set(FlowKeys.ATTEMPTED, false).set(FlowKeys.CREATED, false)
        }
            .asLongAs { session -> session.getBoolean(FlowKeys.TRY_AGAIN) }
            .on(
                selectCandidates
                    .doIf { session -> session.getBoolean(Keys.FLOW_OK) }
                    .then(
                        exec { session -> prepareBooking(session) }
                            .exec(Endpoints.createBooking())
                            .exec { session -> classifyBooking(session, config.conflictRetries).remove(Keys.RESPONSE_BODY) },
                    ).exec { session -> if (session.getBoolean(Keys.FLOW_OK)) session else session.set(FlowKeys.TRY_AGAIN, false) },
            )
    }

    @Suppress("UNCHECKED_CAST")
    private fun availableSeats(session: Session): List<Int> =
        when (val seats = if (session.contains(Keys.AVAILABLE_SEATS)) session.get<Any>(Keys.AVAILABLE_SEATS) else null) {
            null -> emptyList()
            is List<*> -> seats.map { (it as? Number)?.toInt() ?: it.toString().toInt() }
            else -> emptyList()
        }

    private fun prepareBooking(session: Session): Session {
        if (!session.getBoolean(FlowKeys.ATTEMPTED)) LoadMetrics.count(LoadMetrics.FLOW_BOOKING_ATTEMPT)
        return session
            .set(FlowKeys.ATTEMPTED, true)
            .set(Keys.SEAT_IDS, pickSeats(availableSeats(session)))
            // 스냅샷 좌석 목록은 수천 개라 사용자 1만 명이 쥐고 있으면 힙을 크게 차지한다. 고른 뒤에는 버린다
            .remove(Keys.AVAILABLE_SEATS)
            .set(Keys.BOOKING_KEY, Api.newIdempotencyKey("gatling", session.getLong(Keys.VIRTUAL_USER)))
    }

    // 1~4 사이의 장수를 고르고, 후보에서 그만큼 서로 다른 좌석을 무작위로 뽑는다
    private fun pickSeats(candidates: List<Int>): List<Int> {
        val count = minOf(1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(MAX_SEATS_PER_BOOKING), candidates.size)
        return candidates.shuffled().take(count)
    }

    private fun classifyBooking(session: Session, conflictRetries: Int): Session = when {
        Api.isOk(session) -> {
            LoadMetrics.count(LoadMetrics.FLOW_BOOKING_CREATED)
            session.set(FlowKeys.CREATED, true).set(FlowKeys.TRY_AGAIN, false)
        }

        Api.isSeatAlreadyHeld(session) -> {
            LoadMetrics.count(LoadMetrics.BOOKING_CONFLICT)
            val attemptNo = session.getInt(FlowKeys.ATTEMPT_NO) + 1
            session.set(FlowKeys.ATTEMPT_NO, attemptNo).set(FlowKeys.TRY_AGAIN, attemptNo <= conflictRetries)
        }

        else -> session.set(FlowKeys.TRY_AGAIN, false)
    }

    private fun awaitConfirmation(): ChainBuilder = exec { session ->
        session
            .set(FlowKeys.PAID_AT, System.currentTimeMillis())
            .set(FlowKeys.POLL_NO, 0)
            .set(FlowKeys.POLLING, true)
    }.asLongAs { session -> session.getBoolean(FlowKeys.POLLING) }
        .on(
            pause(1)
                .exec(Endpoints.bookingDetail())
                .exec { session -> pollConfirmation(session) },
        )

    private fun pollConfirmation(session: Session): Session {
        val pollNo = session.getInt(FlowKeys.POLL_NO) + 1
        val status = if (Api.isOk(session) && session.contains(Keys.DETAIL_STATUS)) session.getString(Keys.DETAIL_STATUS) else null
        val settledOther = status != null && status != "PENDING"
        return when {
            status == "CONFIRMED" -> {
                LoadMetrics.count(LoadMetrics.BOOKING_CONFIRMED)
                LoadMetrics.count(LoadMetrics.SEATS_CONFIRMED, session.getList<Int>(Keys.SEAT_IDS).size.toLong())
                LoadMetrics.record(LoadMetrics.CONFIRM_LATENCY, System.currentTimeMillis() - session.getLong(FlowKeys.PAID_AT))
                session.set(FlowKeys.POLLING, false)
            }

            settledOther || pollNo >= CONFIRM_POLL_ATTEMPTS -> {
                LoadMetrics.count(LoadMetrics.BOOKING_NOT_CONFIRMED)
                session.set(FlowKeys.POLLING, false)
            }

            else -> session.set(FlowKeys.POLL_NO, pollNo)
        }
    }
}
