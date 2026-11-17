package io.github.ticketing.audit.web

import io.github.ticketing.audit.query.AuditLogQueryRepository
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
class AdminAuditLogController(private val queries: AuditLogQueryRepository) {

    @GetMapping
    fun list(
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") @Min(1) @Max(MAX_PAGE_SIZE.toLong()) size: Int,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) actorId: Long?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): AuditLogQueryRepository.AuditLogPage = queries.find(cursor, size, action, actorId, from, to)
}

private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100
