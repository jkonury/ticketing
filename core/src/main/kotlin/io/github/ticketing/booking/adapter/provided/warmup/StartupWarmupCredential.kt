package io.github.ticketing.booking.adapter.provided.warmup

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.availability.ApplicationAvailability
import org.springframework.boot.availability.ReadinessState
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val STARTUP_WARMUP_HEADER = "X-Startup-Warmup-Key"
internal const val STARTUP_WARMUP_IO_HEADER = "X-Startup-Warmup-Io"
internal const val STARTUP_WARMUP_ROLE = "ROLE_STARTUP_WARMUP"
internal const val WARMUP_WINNER_KEY = "startup-warmup:winner"
internal const val WARMUP_CONFLICT_KEY = "startup-warmup:conflict"

private const val SECRET_BYTES = 32
private const val MAX_REQUESTS = 30000
private const val MAX_CONCURRENCY = 1000
private val WINDOW: Duration = Duration.ofMinutes(3)

/**
 * 예열 요청이 실제 예매 핸들러를 타기 위한 기동 시 비밀 자격이다. 프로세스 메모리에만 있으며 로그·저장소·응답 본문 외에는
 * 내보내지 않는다. 한 번만 발급하고, 생성 후 [WINDOW] 동안 readiness 전환 이후에만 유효하며 호출 수·동시성에 상한이 있다.
 */
@Component
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class StartupWarmupCredential(private val availability: ApplicationAvailability, private val clock: Clock) {
    private val secret: ByteArray = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
    private val encoded: ByteArray = Base64.getUrlEncoder().withoutPadding().encodeToString(secret).toByteArray()
    private val expiresAt: Instant = clock.instant().plus(WINDOW)
    private val issued = AtomicBoolean()
    private val remaining = AtomicInteger(MAX_REQUESTS)
    private val concurrency = Semaphore(MAX_CONCURRENCY)

    private fun active(): Boolean = availability.readinessState == ReadinessState.ACCEPTING_TRAFFIC && clock.instant().isBefore(expiresAt)

    fun issue(): String? = if (active() && issued.compareAndSet(false, true)) String(encoded) else null

    fun matches(candidate: String): Boolean = active() && MessageDigest.isEqual(candidate.toByteArray(), encoded)

    /** 호출 수·동시성 상한 안이면 null, 아니면 거절 상태를 돌려준다. 통과하면 [leave]로 반납해야 한다. */
    fun enter(): HttpStatus? = when {
        remaining.getAndDecrement() <= 0 -> HttpStatus.SERVICE_UNAVAILABLE
        !concurrency.tryAcquire() -> HttpStatus.TOO_MANY_REQUESTS
        else -> null
    }

    fun leave() = concurrency.release()
}

@RestController
@ConditionalOnProperty("ticketing.startup-warmup.enabled", havingValue = "true")
internal class StartupWarmupCredentialController(private val credential: StartupWarmupCredential) {
    @PostMapping("/internal/startup-warmup/credential")
    @PreAuthorize("hasRole('STARTUP_WARMUP')")
    fun issue(): ResponseEntity<Map<String, String>> {
        val key = credential.issue() ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapOf("key" to key))
    }
}
