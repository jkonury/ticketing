package io.github.ticketing.member.repository

import io.github.ticketing.member.domain.Member
import org.springframework.data.repository.Repository
import java.util.Optional

interface MemberRepository : Repository<Member, Long> {
    fun findById(id: Long): Optional<Member>

    fun <S : Member> saveAndFlush(entity: S): S

    fun findByEmail(email: String): Member?

    fun existsByEmail(email: String): Boolean
}
