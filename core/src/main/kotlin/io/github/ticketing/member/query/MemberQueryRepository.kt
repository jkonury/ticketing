package io.github.ticketing.member.query

import io.github.ticketing.member.domain.MemberStatus
import io.github.ticketing.member.domain.Role
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import ticketing.jooq.member.tables.references.MEMBER_
import java.time.Instant

@Repository
class MemberQueryRepository(private val dsl: DSLContext) {

    /** ID 내림차순 커서 페이지. 다음 페이지 존재 여부를 알기 위해 1건 더 읽는다. */
    fun findMembers(cursor: Long?, size: Int, email: String?): MemberPage {
        val conditions = listOfNotNull(
            cursor?.let { MEMBER_.ID.lt(it) },
            email?.takeIf { it.isNotBlank() }?.let { MEMBER_.EMAIL.containsIgnoreCase(it.trim()) },
        )

        val rows = dsl.select(MEMBER_.ID, MEMBER_.EMAIL, MEMBER_.NAME, MEMBER_.ROLE, MEMBER_.STATUS, MEMBER_.CREATED_AT)
            .from(MEMBER_)
            .where(DSL.and(conditions))
            .orderBy(MEMBER_.ID.desc())
            .limit(size + 1)
            .fetch {
                MemberSummary(
                    id = requireNotNull(it[MEMBER_.ID]),
                    email = requireNotNull(it[MEMBER_.EMAIL]),
                    name = requireNotNull(it[MEMBER_.NAME]),
                    role = Role.valueOf(requireNotNull(it[MEMBER_.ROLE])),
                    status = MemberStatus.valueOf(requireNotNull(it[MEMBER_.STATUS])),
                    createdAt = requireNotNull(it[MEMBER_.CREATED_AT]).toInstant(),
                )
            }

        val items = rows.take(size)
        return MemberPage(items, if (rows.size > size) items.last().id else null)
    }

    data class MemberSummary(
        val id: Long,
        val email: String,
        val name: String,
        val role: Role,
        val status: MemberStatus,
        val createdAt: Instant,
    )

    data class MemberPage(val items: List<MemberSummary>, val nextCursor: Long?)
}
