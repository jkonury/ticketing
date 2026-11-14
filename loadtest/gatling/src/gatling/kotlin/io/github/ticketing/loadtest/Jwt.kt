package io.github.ticketing.loadtest

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TOKEN_TTL_SECONDS = 3600L

/** Gateway·core가 검증하는 HS256 access 토큰을 직접 서명한다(로그인 bcrypt를 부하에 넣지 않기 위해서다). */
object Jwt {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun signAccessToken(memberId: Long, role: String): String {
        val now = System.currentTimeMillis() / 1000
        val header = encode("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encode("""{"sub":"$memberId","role":"$role","iat":$now,"exp":${now + TOKEN_TTL_SECONDS}}""".toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(LoadEnv.jwtSecret.toByteArray(), "HmacSHA256"))
        val signature = encode(mac.doFinal("$header.$payload".toByteArray()))
        return "$header.$payload.$signature"
    }

    private fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
}
