package io.github.ticketing.coupon.adapter.provided.web

import io.github.ticketing.coupon.application.port.provided.AdminCouponUseCase
import io.github.ticketing.coupon.application.port.provided.IssuedCouponView
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/issued-coupons")
@PreAuthorize("hasRole('ADMIN')")
class AdminIssuedCouponController(private val admin: AdminCouponUseCase) {

    @PostMapping("/{issuedCouponId}/revoke")
    fun revoke(authentication: Authentication, @PathVariable issuedCouponId: Long): IssuedCouponView =
        admin.revoke(authentication.name.toLong(), issuedCouponId)
}
