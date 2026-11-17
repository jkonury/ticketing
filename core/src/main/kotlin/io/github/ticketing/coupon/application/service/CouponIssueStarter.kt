package io.github.ticketing.coupon.application.service

import io.github.ticketing.coupon.application.port.required.CouponDefinitionCommandPort
import io.github.ticketing.coupon.domain.CouponDefinitionId
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 발급 시작 표시를 별도 트랜잭션으로 저장한다. 낙관적 락 실패는 이 빈의 호출자([CouponIssueStarter])가 다룬다. */
@Service
internal class CouponIssueStartRecorder(private val definitions: CouponDefinitionCommandPort) {
    @Transactional
    fun record(definitionId: CouponDefinitionId, now: Instant) {
        val definition = definitions.findById(definitionId) ?: throw couponNotFound()
        if (definition.issueStarted) return

        definition.markIssueStarted(now)
        definitions.save(definition)
    }
}

/**
 * 오픈 순간 몰리는 받기가 모두 "첫 발급"으로 보고 정의를 저장하면 낙관적 락 충돌로 대량 실패한다.
 * 그래서 시작 표시는 여기서만 저장하고, 충돌하면 다시 읽어 이미 시작됐으면 통과시킨다.
 */
@Service
internal class CouponIssueStarter(private val definitions: CouponDefinitionCommandPort, private val recorder: CouponIssueStartRecorder) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ensureStarted(definitionId: CouponDefinitionId, now: Instant) {
        repeat(MAX_ATTEMPTS - 1) {
            try {
                recorder.record(definitionId, now)
                return
            } catch (e: ObjectOptimisticLockingFailureException) {
                val definition = definitions.findById(definitionId) ?: throw couponNotFound()
                if (definition.issueStarted) return
                log.debug("발급 시작 표시 저장이 충돌해 다시 시도한다. definitionId={}", definitionId.value, e)
            }
        }
        recorder.record(definitionId, now)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
