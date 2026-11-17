package io.github.ticketing.member.service

import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.repository.MemberRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/** 설정한 이메일의 회원을 기동 시 ADMIN으로 승격한다(ADR 0034). 이미 ADMIN이면 아무 일도 없다. */
@Component
class AdminBootstrapRunner(
    private val members: MemberRepository,
    private val adminMembers: AdminMemberService,
    @Value("\${ticketing.admin.bootstrap-email:}") private val bootstrapEmail: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val email = bootstrapEmail.trim().lowercase()
        if (email.isEmpty()) return

        val member = members.findByEmail(email)
        if (member == null) {
            log.warn("관리자 부트스트랩 대상 회원이 없습니다. 먼저 가입해야 합니다.")
            return
        }

        adminMembers.changeRole(null, checkNotNull(member.id), Role.ADMIN)
    }
}
