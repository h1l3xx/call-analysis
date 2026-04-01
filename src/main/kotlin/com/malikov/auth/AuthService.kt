package com.malikov.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.malikov.db.UserRepository
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

sealed class AuthResult {
    data class Success(val tokens: TokenPair, val user: AuthUserDto) : AuthResult()
    data object InvalidCredentials : AuthResult()
    data object UserInactive : AuthResult()
    data object TokenExpired : AuthResult()
    data object TokenRevoked : AuthResult()
    data object TokenNotFound : AuthResult()
}

data class AuthUserDto(
    val id:       String,
    val email:    String,
    val fullName: String,
    val role:     String,
    val tenantId: String?,
)

class AuthService(
    private val userRepo:   UserRepository,
    private val jwtService: JwtService,
) {

    fun login(
        email:      String,
        password:   String,
        deviceInfo: String?,
        ipAddress:  String?,
    ): AuthResult {
        val user = userRepo.findByEmail(email)
            ?: return AuthResult.InvalidCredentials.also {
                logger.warn { "Login failed — user not found: $email" }
            }

        if (!user.isActive) {
            return AuthResult.UserInactive.also {
                logger.warn { "Login failed — user inactive: $email" }
            }
        }

        val passwordValid = BCrypt.verifyer()
            .verify(password.toCharArray(), user.passwordHash)
            .verified

        if (!passwordValid) {
            return AuthResult.InvalidCredentials.also {
                logger.warn { "Login failed — wrong password: $email" }
            }
        }

        val tokens = jwtService.generateTokenPair(
            userId   = user.id,
            tenantId = user.tenantId,
            role     = user.role,
            schema   = user.dbSchema,
        )

        userRepo.saveRefreshToken(
            tokenHash  = jwtService.hashToken(tokens.refreshToken),
            userId     = user.id,
            expiresAt  = tokens.refreshExpiresAt,
            deviceInfo = deviceInfo,
            ipAddress  = ipAddress,
        )

        logger.info { "Login success: userId=${user.id}, role=${user.role}" }

        return AuthResult.Success(tokens = tokens, user = user.toDto())
    }

    fun refresh(refreshToken: String): AuthResult {
        val tokenHash = jwtService.hashToken(refreshToken)
        val stored    = userRepo.findRefreshToken(tokenHash)
            ?: return AuthResult.TokenNotFound

        if (stored.revokedAt != null) {
            logger.warn { "Refresh attempt with revoked token: userId=${stored.userId}" }
            userRepo.revokeAllUserTokens(stored.userId)
            return AuthResult.TokenRevoked
        }

        if (stored.expiresAt < System.currentTimeMillis()) {
            return AuthResult.TokenExpired
        }

        val user = userRepo.findById(stored.userId)
            ?: return AuthResult.InvalidCredentials

        if (!user.isActive) return AuthResult.UserInactive

        userRepo.revokeRefreshToken(tokenHash)

        val tokens = jwtService.generateTokenPair(
            userId   = user.id,
            tenantId = user.tenantId,
            role     = user.role,
            schema   = user.dbSchema,
        )

        userRepo.saveRefreshToken(
            tokenHash  = jwtService.hashToken(tokens.refreshToken),
            userId     = user.id,
            expiresAt  = tokens.refreshExpiresAt,
            deviceInfo = null,
            ipAddress  = null,
        )

        logger.info { "Token refreshed: userId=${user.id}" }

        return AuthResult.Success(tokens = tokens, user = user.toDto())
    }

    fun logout(refreshToken: String) {
        val tokenHash = jwtService.hashToken(refreshToken)
        userRepo.revokeRefreshToken(tokenHash)
        logger.info { "Logout: token revoked" }
    }

    fun logoutAll(userId: UUID) {
        userRepo.revokeAllUserTokens(userId)
        logger.info { "Logout all: userId=$userId" }
    }

    private fun com.malikov.db.UserRow.toDto() = AuthUserDto(
        id       = id.toString(),
        email    = email,
        fullName = fullName,
        role     = role,
        tenantId = tenantId?.toString(),
    )
}