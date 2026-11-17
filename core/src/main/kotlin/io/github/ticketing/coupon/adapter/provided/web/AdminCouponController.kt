package io.github.ticketing.coupon.adapter.provided.web

import io.github.ticketing.coupon.application.port.provided.AdminCouponUseCase
import io.github.ticketing.coupon.application.port.provided.CouponDefinitionView
import io.github.ticketing.coupon.application.port.provided.CouponQueryUseCase
import io.github.ticketing.coupon.application.port.provided.CursorPage
import io.github.ticketing.coupon.application.port.provided.GrantResult
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import io.github.ticketing.coupon.domain.IssuedCouponStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/coupons")
@PreAuthorize("hasRole('ADMIN')")
class AdminCouponController(private val admin: AdminCouponUseCase, private val queries: CouponQueryUseCase) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(authentication: Authentication, @Valid @RequestBody request: CreateCouponRequest): CouponDefinitionView =
        admin.create(request.toCommand(authentication.name.toLong()))

    @PatchMapping("/{id}")
    fun update(
        authentication: Authentication,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateCouponRequest,
    ): CouponDefinitionView = admin.update(request.toCommand(authentication.name.toLong(), id))

    @PostMapping("/{id}/stop")
    fun stop(authentication: Authentication, @PathVariable id: Long): CouponDefinitionView = admin.stop(authentication.name.toLong(), id)

    @GetMapping
    fun list(
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") @Min(1) @Max(MAX_PAGE_SIZE.toLong()) size: Int,
    ): AdminCouponPageResponse = queries.adminList(cursor, size).toResponse()

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): AdminCouponResponse = queries.adminGet(id).toResponse()

    @GetMapping("/{id}/issued")
    fun issued(
        @PathVariable id: Long,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") @Min(1) @Max(MAX_PAGE_SIZE.toLong()) size: Int,
        @RequestParam(required = false) status: IssuedCouponStatus?,
    ): CursorPage<IssuedCouponView> = queries.adminIssued(id, cursor, size, status)

    @PostMapping("/{id}/grants")
    fun grant(authentication: Authentication, @PathVariable id: Long, @Valid @RequestBody request: GrantCouponRequest): GrantResult =
        admin.grant(authentication.name.toLong(), id, requireNotNull(request.memberIds))
}

private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE = 100
