package io.github.ticketing.catalog.web

import io.github.ticketing.catalog.service.PosterUploadService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/performances/{performanceId}/poster-uploads")
@PreAuthorize("hasRole('ORGANIZER')")
class PosterUploadController(private val service: PosterUploadService) {
    @PostMapping
    fun issue(
        authentication: Authentication,
        @PathVariable performanceId: Long,
        @Valid @RequestBody request: PosterUploadRequest,
    ): PosterUploadService.Upload = service.issue(performanceId, authentication.name.toLong(), request.contentType)

    @PostMapping("/{uploadId}/complete")
    fun complete(authentication: Authentication, @PathVariable performanceId: Long, @PathVariable uploadId: UUID): PosterUploadedResponse =
        PosterUploadedResponse(service.complete(performanceId, authentication.name.toLong(), uploadId))
}

data class PosterUploadRequest(@field:NotBlank val contentType: String)

data class PosterUploadedResponse(val posterUrl: String)
