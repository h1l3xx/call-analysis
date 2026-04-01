package com.malikov.routing

import com.malikov.auth.AuthResult
import com.malikov.auth.AuthService
import com.malikov.auth.UserPrincipal
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LoginRequest(
    val email:    String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class TokenResponse(
    val accessToken:      String,
    val refreshToken:     String,
    val accessExpiresAt:  Long,
    val refreshExpiresAt: Long,
    val user: UserResponse,
)

@Serializable
data class UserResponse(
    val id:       String,
    val email:    String,
    val fullName: String,
    val role:     String,
    val tenantId: String?,
)

fun Route.authRoutes(authService: AuthService) {

    // POST /api/v1/auth/login
    post("/login") {
        val body = call.receive<LoginRequest>()

        if (body.email.isBlank() || body.password.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Email and password required"))
            return@post
        }

        val deviceInfo = call.request.headers["User-Agent"]
        val ipAddress  = call.request.origin.remoteHost

        when (val result = authService.login(body.email.trim(), body.password, deviceInfo, ipAddress)) {
            is AuthResult.Success -> call.respond(HttpStatusCode.OK, result.toResponse())

            is AuthResult.InvalidCredentials ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid email or password"))

            is AuthResult.UserInactive ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Account is inactive"))

            else ->
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Unexpected error"))
        }
    }

    // POST /api/v1/auth/refresh
    post("/refresh") {
        val body = call.receive<RefreshRequest>()

        if (body.refreshToken.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Refresh token required"))
            return@post
        }

        when (val result = authService.refresh(body.refreshToken)) {
            is AuthResult.Success ->
                call.respond(HttpStatusCode.OK, result.toResponse())

            is AuthResult.TokenExpired ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Refresh token expired"))

            is AuthResult.TokenRevoked ->
                call.respond(HttpStatusCode.Unauthorized, mapOf(
                    "error" to "Token revoked — all sessions terminated for security"
                ))

            is AuthResult.TokenNotFound ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid refresh token"))

            is AuthResult.UserInactive ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Account is inactive"))

            else ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
        }
    }

    // POST /api/v1/auth/logout  (требует JWT)
    authenticate("jwt") {
        post("/logout") {
            val body = runCatching { call.receive<RefreshRequest>() }.getOrNull()
            val principal = call.principal<UserPrincipal>()!!

            if (body?.refreshToken != null) {
                authService.logout(body.refreshToken)
            }

            call.respond(HttpStatusCode.OK, mapOf("message" to "Logged out"))
        }

        // POST /api/v1/auth/logout-all — отзывает все сессии
        post("/logout-all") {
            val principal = call.principal<UserPrincipal>()!!
            authService.logoutAll(UUID.fromString(principal.userId))
            call.respond(HttpStatusCode.OK, mapOf("message" to "All sessions terminated"))
        }

        // GET /api/v1/auth/me
        get("/me") {
            val principal = call.principal<UserPrincipal>()!!
            call.respond(HttpStatusCode.OK, mapOf(
                "id"       to principal.userId,
                "role"     to principal.role,
                "tenantId" to principal.tenantId,
                "schema"   to principal.schema,
            ))
        }
    }
}

private fun AuthResult.Success.toResponse() = TokenResponse(
    accessToken      = tokens.accessToken,
    refreshToken     = tokens.refreshToken,
    accessExpiresAt  = tokens.accessExpiresAt,
    refreshExpiresAt = tokens.refreshExpiresAt,
    user = UserResponse(
        id       = user.id,
        email    = user.email,
        fullName = user.fullName,
        role     = user.role,
        tenantId = user.tenantId,
    ),
)