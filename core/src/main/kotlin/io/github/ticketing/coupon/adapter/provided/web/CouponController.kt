package io.github.ticketing.coupon.adapter.provided.web

import io.github.ticketing.coupon.application.port.provided.ClaimCouponUseCase
import io.github.ticketing.coupon.application.port.provided.CouponQueryUseCase
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@PreAuthorize("isAuthenticated()")
class CouponController(private val claimCoupon: ClaimCouponUseCase, private val queries: CouponQueryUseCase) {

    @GetMapping("/api/v1/coupons")
    fun claimable(authentication: Authentication): List<ClaimableCouponResponse> =
        queries.claimable(authentication.name.toLong()).map { it.toResponse() }

    @PostMapping("/api/v1/coupons/{definitionId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    fun claim(authentication: Authentication, @PathVariable definitionId: Long): IssuedCouponView =
        claimCoupon.claim(authentication.name.toLong(), definitionId)

    @GetMapping("/api/v1/me/coupons")
    fun myCoupons(authentication: Authentication): List<MyCouponResponse> =
        queries.myCoupons(authentication.name.toLong()).map { it.toResponse() }
}
