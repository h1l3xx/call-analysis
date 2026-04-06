package com.malikov.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.malikov.auth.UserPrincipal
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun Application.configurePlugins(config: AppConfig) {
    install(AutoHeadResponse)
    install(PartialContent) { maxRangeCount = 10 }
    configureContentNegotiation()
    configureCors()
    configureAuth(config.jwt)
    configureStatusPages()
}

private fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
}

private fun Application.configureCors() {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowCredentials = true
        // В продакшене заменить на конкретный домен
        anyHost()
    }
}

fun Application.configureAuth(config: JwtConfig) {
    val algorithm = Algorithm.HMAC256(config.secret)

    install(Authentication) {
        jwt("jwt") {
            verifier(
                JWT.require(algorithm)
                    .withIssuer(config.issuer)
                    .build()
            )
            validate { credential ->
                val userId   = credential.payload.subject ?: return@validate null
                val tenantId = credential.payload.getClaim("tenant_id").asString()
                val role     = credential.payload.getClaim("role").asString() ?: return@validate null

                UserPrincipal(
                    userId   = userId,
                    tenantId = tenantId,   // null для SUPERADMIN
                    role     = role,
                    schema   = credential.payload.getClaim("schema").asString(),
                )
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or expired token"))
            }
        }
    }
}

class NotFoundException(message: String) : RuntimeException(message)
class ForbiddenException(message: String) : RuntimeException(message)

private fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to (cause.message ?: "Not found"))
            )
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to (cause.message ?: "Forbidden"))
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "Bad request"))
            )
        }
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception: ${cause.message}" }
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error")
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found"))
        }
    }
}
