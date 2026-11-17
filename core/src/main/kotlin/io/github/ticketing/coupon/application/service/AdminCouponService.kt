package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.CouponDefinitionCreated
import io.github.ticketing.coupon.CouponDefinitionStopped
import io.github.ticketing.coupon.CouponDefinitionUpdated
import io.github.ticketing.coupon.CouponRevoked
import io.github.ticketing.coupon.CouponsGranted
import io.github.ticketing.coupon.application.port.provided.AdminCouponUseCase
import io.github.ticketing.coupon.application.port.provided.CouponDefinitionView
import io.github.ticketing.coupon.application.port.provided.CreateCouponCommand
import io.github.ticketing.coupon.application.port.provided.GrantResult
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.application.port.provided.UpdateCouponCommand
import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.application.port.required.CouponQueryPort
import io.github.ticketing.coupon.application.port.required.IssueResult
import io.github.ticketing.coupon.application.port.required.IssuedCouponCommandPort
import io.github.ticketing.coupon.domain.CouponDefinition
import io.github.ticketing.coupon.domain.CouponDefinitionId
import io.github.ticketing.coupon.domain.DefinitionStatus
import io.github.ticketing.coupon.domain.DiscountPolicy
import io.github.ticketing.coupon.domain.IssueMode
import io.github.ticketing.coupon.domain.IssuedCoupon
import io.github.ticketing.coupon.domain.IssuedCouponId
import io.github.ticketing.coupon.domain.MemberId
import io.github.ticketing.coupon.domain.PerformanceId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class AdminCouponService(
    private val definitions: CouponDefinitionCommandPort,
    private val issuedCoupons: IssuedCouponCommandPort,
    private val queries: CouponQueryPort,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : AdminCouponUseCase {

    @Transactional
    override fun create(command: CreateCouponCommand): CouponDefinitionView {
        val now = clock.instant()
        val policy = DiscountPolicy(
            type = command.discountType,
            value = BigDecimal(command.discountValue),
            maxDiscount = command.maxDiscount?.let(::BigDecimal),
            minOrderAmount = BigDecimal(command.minOrderAmount),
            performanceId = command.performanceId?.let(::PerformanceId),
            validFrom = command.validFrom,
            validUntil = command.validUntil,
        )
        val definition = translateRules {
            CouponDefinition.create(command.name, command.description, policy, command.issueMode, command.totalQuantity, now)
        }

        val saved = definitions.save(definition)

        events.publishEvent(CouponDefinitionCreated(UUID.randomUUID(), command.actorId, requireNotNull(saved.id).value, saved.name, now))
        return saved.toView()
    }

    @Transactional
    override fun update(command: UpdateCouponCommand): CouponDefinitionView {
        val definition = load(command.definitionId)
        val changedFields = command.providedFields()
        if (changedFields.isEmpty()) return definition.toView()

        translateRules {
            if (command.name != null || command.description != null) {
                definition.rename(command.name ?: definition.name, command.description ?: definition.description)
            }
            command.validUntil?.let(definition::extendValidUntil)
            if (command.changesTerms) applyTerms(definition, command)
        }

        val saved = definitions.save(definition)

        events.publishEvent(
            CouponDefinitionUpdated(UUID.randomUUID(), command.actorId, command.definitionId, changedFields, clock.instant()),
        )
        return saved.toView()
    }

    /** 이미 중지된 정의는 상태도 이벤트도 바꾸지 않는다. */
    @Transactional
    override fun stop(actorId: Long, definitionId: Long): CouponDefinitionView {
        val definition = load(definitionId)
        if (definition.status == DefinitionStatus.STOPPED) return definition.toView()

        definition.stop()
        val saved = definitions.save(definition)

        events.publishEvent(CouponDefinitionStopped(UUID.randomUUID(), actorId, definitionId, clock.instant()))
        return saved.toView()
    }

    /** 정의가 ACTIVE이면 발급 방식·기간과 무관하게 지급한다(관리자 직접 지급). 회원 존재 여부는 member 모듈 경계상 검증하지 않는다. */
    @Transactional
    override fun grant(actorId: Long, definitionId: Long, memberIds: List<Long>): GrantResult {
        val definition = load(definitionId)
        if (definition.status != DefinitionStatus.ACTIVE) throw couponNotIssuable()

        val now = clock.instant()
        val granted = mutableListOf<Long>()
        val alreadyClaimed = mutableListOf<Long>()
        memberIds.distinct().forEach { memberId ->
            when (issuedCoupons.issue(IssuedCoupon.issue(CouponDefinitionId(definitionId), MemberId(memberId), now))) {
                is IssueResult.Issued -> granted += memberId
                IssueResult.AlreadyClaimed -> alreadyClaimed += memberId
            }
        }

        if (granted.isNotEmpty()) {
            markIssueStarted(definition, now)
            events.publishEvent(CouponsGranted(UUID.randomUUID(), actorId, definitionId, granted.toList(), now))
        }
        return GrantResult(granted, alreadyClaimed)
    }

    @Transactional
    override fun revoke(actorId: Long, issuedCouponId: Long): IssuedCouponView {
        val issued = issuedCoupons.findById(IssuedCouponId(issuedCouponId)) ?: throw couponNotFound()
        if (!issuedCoupons.revokeIfIssued(IssuedCouponId(issuedCouponId))) throw couponNotRevocable()

        val now = clock.instant()
        events.publishEvent(
            CouponRevoked(UUID.randomUUID(), actorId, issuedCouponId, issued.definitionId.value, issued.memberId.value, now),
        )
        return checkNotNull(queries.findIssuedById(issuedCouponId, now)) { "회수한 쿠폰을 조회할 수 없다: $issuedCouponId" }
    }

    private fun load(definitionId: Long): CouponDefinition =
        definitions.findById(CouponDefinitionId(definitionId)) ?: throw couponNotFound()

    private fun markIssueStarted(definition: CouponDefinition, now: Instant) {
        if (definition.issueStarted) return
        definition.markIssueStarted(now)
        definitions.save(definition)
    }

    private fun applyTerms(definition: CouponDefinition, command: UpdateCouponCommand) {
        val current = definition.discountPolicy
        val mode = command.issueMode ?: definition.issueMode
        val quantity = when {
            command.totalQuantity != null -> command.totalQuantity
            mode == IssueMode.LIMITED && definition.issueMode == IssueMode.LIMITED -> definition.totalQuantity
            else -> null
        }
        val policy = current.copy(
            type = command.discountType ?: current.type,
            value = command.discountValue?.let(::BigDecimal) ?: current.value,
            maxDiscount = command.maxDiscount?.let(::BigDecimal) ?: current.maxDiscount,
            minOrderAmount = command.minOrderAmount?.let(::BigDecimal) ?: current.minOrderAmount,
            performanceId = command.performanceId?.let(::PerformanceId) ?: current.performanceId,
            validFrom = command.validFrom ?: current.validFrom,
        )
        definition.changeTerms(policy, mode, quantity)
    }
}
