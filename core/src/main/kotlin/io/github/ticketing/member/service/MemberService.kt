package io.github.ticketing.member.service

import io.github.ticketing.member.domain.Member
import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.repository.MemberRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class MemberService(private val members: MemberRepository, private val passwordEncoder: PasswordEncoder, private val clock: Clock) {

    @Transactional
    fun signUp(command: SignUpCommand): Member {
        rejectAdminRole(command.role)
        val email = command.email.trim().lowercase()
        if (members.existsByEmail(email)) throw EmailAlreadyExistsException()
        val member = Member(
            email = email,
            passwordHash = checkNotNull(passwordEncoder.encode(command.password)),
            name = command.name.trim(),
            role = command.role,
            createdAt = clock.instant(),
        )
        // exists 확인과 저장 사이에 같은 이메일이 들어오면 유니크 제약이 막는다. 500 대신 409로 변환한다
        return try {
            members.saveAndFlush(member)
        } catch (e: DataIntegrityViolationException) {
            throw EmailAlreadyExistsException().also { it.initCause(e) }
        }
    }

    // ADMIN은 부트스트랩이나 관리자 API로만 부여한다 (ADR 0034)
    private fun rejectAdminRole(role: Role) {
        if (role == Role.ADMIN) throw RoleNotAllowedException()
    }

    @Transactional(readOnly = true)
    fun get(memberId: Long): Member = members.findById(memberId).orElseThrow { MemberNotFoundException() }

    data class SignUpCommand(val email: String, val password: String, val name: String, val role: Role)
}
