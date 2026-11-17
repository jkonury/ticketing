package io.github.ticketing.coupon.domain

import java.time.Instant

enum class IssueMode { UNLIMITED, LIMITED, DIRECT }

enum class DefinitionStatus { ACTIVE, STOPPED }

class CouponDefinition(
    val id: CouponDefinitionId?,
    name: String,
    description: String?,
    discountPolicy: DiscountPolicy,
    issueMode: IssueMode,
    totalQuantity: Int?,
    status: DefinitionStatus,
    issueStartedAt: Instant?,
    val createdAt: Instant,
    val version: Long,
) {
    var name: String = name
        private set
    var description: String? = description
        private set
    var discountPolicy: DiscountPolicy = discountPolicy
        private set
    var issueMode: IssueMode = issueMode
        private set
    var totalQuantity: Int? = totalQuantity
        private set
    var status: DefinitionStatus = status
        private set
    var issueStartedAt: Instant? = issueStartedAt
        private set

    init {
        require(name.isNotBlank()) { "쿠폰 이름은 비어 있을 수 없다" }
        require(discountPolicy.value.signum() > 0) { "할인 값은 양수여야 한다" }
        require(discountPolicy.validFrom.isBefore(discountPolicy.validUntil)) { "유효 기간이 올바르지 않다" }
        require((issueMode == IssueMode.LIMITED) == (totalQuantity != null)) { "수량은 LIMITED일 때만 지정한다" }
        require(totalQuantity == null || totalQuantity > 0) { "수량은 양수여야 한다" }
    }

    val validUntil: Instant get() = discountPolicy.validUntil

    val issueStarted: Boolean get() = issueStartedAt != null

    fun canIssue(now: Instant): Boolean =
        status == DefinitionStatus.ACTIVE && now >= discountPolicy.validFrom && now.isBefore(discountPolicy.validUntil)

    /** 첫 발급 시 한 번만 기록한다. 이후 할인 조건은 잠긴다. */
    fun markIssueStarted(now: Instant) {
        if (issueStartedAt == null) issueStartedAt = now
    }

    /** 신규 발급만 막는다. 이미 발급된 쿠폰의 사용에는 영향이 없다. */
    fun stop() {
        status = DefinitionStatus.STOPPED
    }

    fun rename(name: String, description: String?) {
        require(name.isNotBlank()) { "쿠폰 이름은 비어 있을 수 없다" }
        this.name = name
        this.description = description
    }

    fun extendValidUntil(newValidUntil: Instant) {
        if (newValidUntil.isBefore(discountPolicy.validUntil)) throw ValidUntilShortenedException()
        discountPolicy = discountPolicy.copy(validUntil = newValidUntil)
    }

    /** 발급이 시작되기 전에만 할인 조건·발급 방식·수량을 바꿀 수 있다. */
    fun changeTerms(newPolicy: DiscountPolicy, newIssueMode: IssueMode, newTotalQuantity: Int?) {
        if (issueStarted) throw DefinitionLockedException("terms")
        require(newPolicy.value.signum() > 0) { "할인 값은 양수여야 한다" }
        require(newPolicy.validFrom.isBefore(newPolicy.validUntil)) { "유효 기간이 올바르지 않다" }
        require((newIssueMode == IssueMode.LIMITED) == (newTotalQuantity != null)) { "수량은 LIMITED일 때만 지정한다" }
        require(newTotalQuantity == null || newTotalQuantity > 0) { "수량은 양수여야 한다" }
        discountPolicy = newPolicy
        issueMode = newIssueMode
        totalQuantity = newTotalQuantity
    }

    companion object {
        fun create(
            name: String,
            description: String?,
            discountPolicy: DiscountPolicy,
            issueMode: IssueMode,
            totalQuantity: Int?,
            now: Instant,
        ) = CouponDefinition(null, name, description, discountPolicy, issueMode, totalQuantity, DefinitionStatus.ACTIVE, null, now, 0)
    }
}
