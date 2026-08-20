package com.example.mypasture.auth.infrastructure

import com.example.mypasture.config.JwtProperties
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import com.example.mypasture.user.domain.SubscriptionTier
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 사용자 정보를 HS256 JWT access token으로 발급하고 검증하는 infrastructure component입니다. */
@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun issueAccessToken(user: UserEntity): IssuedJwtToken {
        require(jwtProperties.secret.toByteArray(StandardCharsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "JWT secret must be at least $MIN_SECRET_BYTES bytes"
        }

        val issuedAt = Instant.now(clock)
        val expiresAt = issuedAt.plusSeconds(jwtProperties.accessTokenTtlSeconds)
        val header = mapOf("alg" to ALGORITHM, "typ" to "JWT")
        val payload = mapOf(
            "iss" to jwtProperties.issuer,
            "sub" to user.id.toString(),
            "email" to user.email,
            "displayName" to user.displayName,
            "role" to user.role.name,
            "subscriptionTier" to user.subscriptionTier.name,
            "authVersion" to user.authVersion,
            "iat" to issuedAt.epochSecond,
            "exp" to expiresAt.epochSecond,
        )

        val unsignedToken = "${encodeJson(header)}.${encodeJson(payload)}"
        val signature = sign(unsignedToken)
        return IssuedJwtToken(
            accessToken = "$unsignedToken.$signature",
            tokenType = "Bearer",
            expiresIn = jwtProperties.accessTokenTtlSeconds,
            expiresAt = expiresAt,
        )
    }

    fun parseAccessToken(token: String): JwtPrincipal {
        val parts = token.split('.')
        if (parts.size != 3) throw InvalidJwtTokenException()

        val unsignedToken = "${parts[0]}.${parts[1]}"
        val expectedSignature = sign(unsignedToken)
        if (!MessageDigest.isEqual(expectedSignature.toByteArray(StandardCharsets.UTF_8), parts[2].toByteArray(StandardCharsets.UTF_8))) {
            throw InvalidJwtTokenException()
        }

        val claims = objectMapper.readTree(decoder.decode(parts[1]))
        if (claims.path("iss").asText() != jwtProperties.issuer) throw InvalidJwtTokenException()

        val expiresAt = Instant.ofEpochSecond(claims.path("exp").asLong(0))
        if (!expiresAt.isAfter(Instant.now(clock))) throw ExpiredJwtTokenException()

        return JwtPrincipal(
            userId = UUID.fromString(claims.path("sub").asText()),
            email = claims.path("email").takeUnless { it.isMissingNode || it.isNull }?.asText(),
            displayName = claims.path("displayName").asText(),
            role = UserRole.valueOf(claims.path("role").asText()),
            subscriptionTier = SubscriptionTier.valueOf(claims.path("subscriptionTier").asText()),
            authVersion = claims.path("authVersion").asInt(0),
            issuedAt = Instant.ofEpochSecond(claims.path("iat").asLong(0)),
        )
    }

    private fun encodeJson(value: Any): String = encoder.encodeToString(objectMapper.writeValueAsBytes(value))

    private fun sign(unsignedToken: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(jwtProperties.secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(unsignedToken.toByteArray(StandardCharsets.UTF_8)))
    }

    companion object {
        private const val ALGORITHM = "HS256"
        private const val MIN_SECRET_BYTES = 32
    }
}

/** 로그인 성공 시 클라이언트로 내려주는 access token 메타데이터입니다. */
data class IssuedJwtToken(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val expiresAt: Instant,
)

/** Spring Security 인증 객체에 담기는 JWT 사용자 principal입니다. */
data class JwtPrincipal(
    val userId: UUID,
    val email: String?,
    val displayName: String,
    val role: UserRole,
    val subscriptionTier: SubscriptionTier,
    val authVersion: Int,
    val issuedAt: Instant = Instant.EPOCH,
)

/** JWT 형식, 서명, issuer, claim이 유효하지 않을 때 발생하는 예외입니다. */
open class JwtAuthenticationException(message: String) : RuntimeException(message)

/** 만료된 JWT가 전달되었을 때 발생하는 예외입니다. */
class ExpiredJwtTokenException : JwtAuthenticationException("JWT token is expired")

/** 검증할 수 없는 JWT가 전달되었을 때 발생하는 예외입니다. */
class InvalidJwtTokenException : JwtAuthenticationException("JWT token is invalid")
