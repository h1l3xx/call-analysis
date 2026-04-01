package com.malikov.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.malikov.config.JwtConfig
import java.security.MessageDigest
import java.util.Date
import java.util.UUID

data class TokenPair(
    val accessToken:      String,
    val refreshToken:     String,
    val accessExpiresAt:  Long,   // Unix ms
    val refreshExpiresAt: Long,   // Unix ms
)

class JwtService(private val config: JwtConfig) {

    private val algorithm = Algorithm.HMAC256(config.secret)

    fun generateTokenPair(
        userId:   UUID,
        tenantId: UUID?,
        role:     String,
        schema:   String?,
    ): TokenPair {
        val now              = System.currentTimeMillis()
        val accessExpiresAt  = now + config.accessTtlMin * 60 * 1000
        val refreshExpiresAt = now + config.refreshTtlDays * 86400 * 1000

        val accessToken = JWT.create()
            .withIssuer(config.issuer)
            .withSubject(userId.toString())
            .withClaim("tenant_id", tenantId?.toString())
            .withClaim("role", role)
            .withClaim("schema", schema)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(accessExpiresAt))
            .sign(algorithm)

        val refreshToken = UUID.randomUUID().toString() + "." + UUID.randomUUID().toString()

        return TokenPair(
            accessToken      = accessToken,
            refreshToken     = refreshToken,
            accessExpiresAt  = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt,
        )
    }

    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}