package io.github.ticketing.member.web

import io.github.ticketing.member.domain.MemberStatus
import io.github.ticketing.member.domain.Role
import io.github.ticketing.member.query.MemberQueryRepository
import io.github.ticketing.member.service.AdminMemberService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/members")
@PreAuthorize("hasRole('ADMIN')")
class AdminMemberController(private val queries: MemberQueryRepository, private val adminMembers: AdminMemberService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") @Min(1) @Max(MAX_PAGE_SIZE.toLong()) size: Int,
        @RequestParam(required = false) email: String?,
    ): MemberQueryRepository.MemberPage = queries.findMembers(cursor, size, email)

    @PutMapping("/{id}/role")
    fun changeRole(@PathVariable id: Long, @RequestBody request: ChangeRoleRequest, authentication: Authentication): MemberResponse =
        MemberResponse.from(adminMembers.changeRole(authentication.name.toLong(), id, request.role))

    @PutMapping("/{id}/suspension")
    fun suspendMember(
        @PathVariable id: Long,
        @RequestBody @Valid request: SuspendMemberRequest,
        authentication: Authentication,
    ): MemberSuspensionResponse {
        val member = adminMembers.suspendMember(authentication.name.toLong(), id, request.reason)
        return MemberSuspensionResponse(id, member.status)
    }

    @DeleteMapping("/{id}/suspension")
    fun reinstate(@PathVariable id: Long, authentication: Authentication): MemberSuspensionResponse {
        val member = adminMembers.reinstate(authentication.name.toLong(), id)
        return MemberSuspensionResponse(id, member.status)
    }
}

private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100

data class ChangeRoleRequest(val role: Role)

data class SuspendMemberRequest(@field:NotBlank @field:Size(max = 200) val reason: String)

data class MemberSuspensionResponse(val id: Long, val status: MemberStatus)
