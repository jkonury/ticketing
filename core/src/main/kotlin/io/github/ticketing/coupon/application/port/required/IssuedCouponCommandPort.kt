package io.github.ticketing.coupon.application.port.required

import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.MemberId
import io.github.ticketing.coupon.domain.OrderId
import java.math.BigDecimal
import java.time.Instant

sealed interface IssueResult {
    data class Issued(val coupon: IssuedCoupon) : IssueResult

    /** (정의, 회원) 유니크 제약에 걸렸다. 이미 발급받은 회원이다. */
    data object AlreadyClaimed : IssueResult
}

/**
 * 상태 전이 메서드는 모두 조건부 UPDATE로 구현하며, 조건에 맞아 갱신됐으면 true, 아니면 false를 돌려준다.
 * 같은 요청의 재전달은 false가 되므로 호출자는 멱등하게 무시할 수 있다.
 */
interface IssuedCouponCommandPort {
    /** 유니크 위반은 예외가 아니라 [IssueResult.AlreadyClaimed]로 돌려준다(호출 트랜잭션을 오염시키지 않는다). */
    fun issue(coupon: IssuedCoupon): IssueResult

    fun findById(id: IssuedCouponId): IssuedCoupon?

    /** 정의에 발급된 모든 회원. 회수·사용 여부와 무관하게 (정의, 회원) 유니크 기준으로 이미 받은 회원이다. */
    fun findMemberIdsByDefinition(definitionId: CouponDefinitionId): List<MemberId>

    fun findByDefinitionAndMember(definitionId: CouponDefinitionId, memberId: MemberId): IssuedCoupon?

    /** 발급 행 잠금은 트랜잭션이 끝날 때까지 유지된다. */
    fun findForUpdate(id: IssuedCouponId): IssuedCoupon?

    fun reserveIfAvailable(
        id: IssuedCouponId,
        orderId: OrderId,
        discountAmount: BigDecimal,
        reservedUntil: Instant,
        attemptId: Long,
    ): Boolean

    fun releaseIfReservedBy(id: IssuedCouponId, orderId: OrderId, attemptId: Long): Boolean

    fun confirmIfReservedBy(id: IssuedCouponId, orderId: OrderId, now: Instant, attemptId: Long): Boolean

    fun restoreReleasedUse(id: IssuedCouponId, orderId: OrderId, now: Instant, attemptId: Long, releasedVersion: Long): Boolean

    fun revokeIfIssued(id: IssuedCouponId): Boolean
}
