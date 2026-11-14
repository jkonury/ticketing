package io.github.ticketing.loadtest

import java.util.concurrent.atomic.AtomicLong

class ConflictPair(val pair: Int, val seatsA: List<Int>, val seatsB: List<Int>)

class FirstBooking(val pair: Int, val memberId: Long, val bookingId: Long)

/** seed.sh가 만든 members.json과 schedule.json을 읽는다. 값은 시뮬레이션 시작 시 한 번만 읽는다. */
object LoadData {
    private val members by lazy { LoadEnv.readJson("MEMBERS_FILE") }
    private val schedule by lazy { LoadEnv.readJson("SCHEDULE_FILE") }

    val scheduleId: Long by lazy { schedule.path("scheduleId").asLong() }

    val hotSeatIds: List<Int> by lazy { schedule.path("hotSeatIds").map { it.asInt() } }

    val conflictPairs: List<ConflictPair> by lazy {
        schedule.path("conflictPairs").map { node ->
            ConflictPair(
                pair = node.path("pair").asInt(),
                seatsA = node.path("seatsA").map { it.asInt() },
                seatsB = node.path("seatsB").map { it.asInt() },
            )
        }
    }

    // 둘째 단계에서만 쓴다. runner가 첫 단계 로그에서 만든 파일이다
    val firstBookings: Map<Int, FirstBooking> by lazy {
        LoadEnv.readJson("FIRST_FILE").associate { node ->
            node.path("pair").asInt() to
                FirstBooking(node.path("pair").asInt(), node.path("memberId").asLong(), node.path("bookingId").asLong())
        }
    }

    fun pairAt(pair: Int): ConflictPair {
        val found = conflictPairs.getOrNull(pair - 1)
        check(found != null && found.pair == pair) { "좌석 그룹 $pair 이 시드에 없습니다(conflictPairs ${conflictPairs.size}개)." }
        return found
    }

    fun firstBookingOf(pair: Int): FirstBooking = checkNotNull(firstBookings[pair]) { "그룹 $pair 의 첫 예매가 FIRST_FILE에 없습니다." }

    /** VU마다 다른 회원을 쓴다. 회원 한 명을 공유하면 회원별 rate limit과 대기열 항목이 겹친다. */
    fun memberIdFor(virtualUser: Long): Long {
        val min = members.path("userIdMin").asLong()
        val max = members.path("userIdMax").asLong()
        val memberId = min + virtualUser - 1
        check(memberId <= max) { "VU $virtualUser 에 배정할 회원이 없습니다(회원 ID $min~$max)." }
        return memberId
    }

    private val virtualUserSequence = AtomicLong(0)

    /** 시뮬레이션 안에서 1부터 순서대로 VU 번호를 준다(k6의 exec.vu.idInTest 대응). */
    fun nextVirtualUser(): Long = virtualUserSequence.incrementAndGet()
}
