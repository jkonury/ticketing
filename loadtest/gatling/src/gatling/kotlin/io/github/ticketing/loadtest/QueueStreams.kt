package io.github.ticketing.loadtest

import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.regex
import io.gatling.javaapi.core.CoreDsl.substring
import io.gatling.javaapi.core.Session
import io.gatling.javaapi.http.HttpDsl.sse
import java.time.Duration

private val QUEUE_STREAM_TIMEOUT = Duration.ofMinutes(16)
private val SEAT_STREAM_TIMEOUT = Duration.ofMinutes(1)

// 스냅샷은 좌석 수천 개의 JSON이다. 전체를 파싱하지 않고 예매 후보가 되는 AVAILABLE 좌석 ID만 정규식으로 뽑는다.
// 필드 순서는 realtime의 SeatSnapshot(seatId, status, holdExpiresAt)을 따른다.
private const val AVAILABLE_SEAT = """"seatId":(\d+),"status":"AVAILABLE""""

/**
 * SSE 스트림 헬퍼. 스트림을 열 때마다 SSE 티켓을 새로 발급한다.
 * Gatling은 SSE 메시지를 `{"event":"이름","data":...}` 형태의 JSON 문자열로 만들어 체크에 넘긴다.
 * 실패하면 [Keys.FLOW_OK]를 false로 둔다.
 */
object QueueStreams {
    /** 순번 스트림을 열고 admitted까지 기다린다. removed·오류·시간 초과면 FLOW_OK가 false다. */
    fun waitForAdmission(): ChainBuilder = exec { session -> session.set(Keys.FLOW_OK, true) }
        .exec(Endpoints.issueSseTicket())
        .exec { session ->
            if (session.contains(Keys.TICKET)) {
                session
            } else {
                // 티켓 발급 실패는 sse_ticket 요청에서 이미 오류로 센다
                LoadMetrics.count(LoadMetrics.QUEUE_STREAM_TICKET_FAILED)
                session.set(Keys.FLOW_OK, false)
            }
        }.doIf { session -> session.getBoolean(Keys.FLOW_OK) }
        .then(
            exec { session -> session.markAsSucceeded().remove(Keys.QUEUE_EVENT).remove(Keys.ADMISSION_TOKEN) }
                .exec(
                    sse("sse_queue")
                        .get("/api/v1/streams/queues/#{${Keys.SCHEDULE_ID}}?ticket=#{${Keys.TICKET}}")
                        .await(QUEUE_STREAM_TIMEOUT)
                        .on(
                            sse
                                .checkMessage("sse_queue_result")
                                .matching(jsonPath("$.event").`in`("admitted", "removed"))
                                .check(
                                    jsonPath("$.event").saveAs(Keys.QUEUE_EVENT),
                                    jsonPath("$.data.admissionToken").optional().saveAs(Keys.ADMISSION_TOKEN),
                                ),
                        ),
                ).doIf { session -> !session.isFailed }
                .then(exec(sse("sse_queue_close").close()))
                .exec { session -> classifyAdmission(session) },
        )

    // 대기열 이탈은 하지 않는다. 입장 토큰 TTL로 정리된다
    private fun classifyAdmission(session: Session): Session = when {
        session.isFailed -> {
            LoadMetrics.count(LoadMetrics.QUEUE_STREAM_CLOSED_WITHOUT_RESULT)
            LoadMetrics.count(LoadMetrics.UNEXPECTED_ERRORS)
            session.markAsSucceeded().set(Keys.FLOW_OK, false)
        }

        session.getString(Keys.QUEUE_EVENT) == "removed" -> {
            LoadMetrics.count(LoadMetrics.QUEUE_STREAM_REMOVED)
            LoadMetrics.count(LoadMetrics.UNEXPECTED_ERRORS)
            session.set(Keys.FLOW_OK, false)
        }

        else -> session
    }

    /**
     * 좌석 스트림은 snapshot 이벤트를 받으면 닫는다. 스냅샷의 AVAILABLE 좌석 ID를
     * 세션의 [Keys.AVAILABLE_SEATS](List<Int>)에 저장한다. 실패하면 FLOW_OK가 false다.
     */
    fun readSeatSnapshot(): ChainBuilder = exec { session -> session.set(Keys.FLOW_OK, true) }
        .exec(Endpoints.issueSseTicket())
        .exec { session -> if (session.contains(Keys.TICKET)) session else session.set(Keys.FLOW_OK, false) }
        .doIf { session -> session.getBoolean(Keys.FLOW_OK) }
        .then(
            exec { session -> session.markAsSucceeded().remove(Keys.AVAILABLE_SEATS) }
                .exec(
                    sse("sse_seats")
                        .get("/api/v1/streams/schedules/#{${Keys.SCHEDULE_ID}}/seats?ticket=#{${Keys.TICKET}}")
                        .await(SEAT_STREAM_TIMEOUT)
                        .on(
                            sse
                                .checkMessage("sse_seats_snapshot")
                                .matching(substring("\"event\":\"snapshot\""))
                                // 좌석이 하나도 없으면(매진) 매치가 없으므로 optional로 비어 있는 목록을 허용한다
                                .check(regex(AVAILABLE_SEAT).findAll().optional().saveAs(Keys.AVAILABLE_SEATS)),
                        ),
                ).doIf { session -> !session.isFailed }
                .then(exec(sse("sse_seats_close").close()))
                .exec { session ->
                    if (session.isFailed) {
                        LoadMetrics.count(LoadMetrics.UNEXPECTED_ERRORS)
                        session.markAsSucceeded().set(Keys.FLOW_OK, false)
                    } else {
                        session
                    }
                },
        )
}
