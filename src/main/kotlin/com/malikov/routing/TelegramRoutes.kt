package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.auth.UserPrincipal
import com.malikov.db.DepartmentLeadRepository
import com.malikov.telegram.TelegramLinkService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LinkCodeResponse(val code: String, val ttlMinutes: Long)

@Serializable
data class TelegramStatusResponse(val linked: Boolean, val pendingCode: String?)

@Serializable
data class AssignLeadRequest(val userId: String)

@Serializable
data class DepartmentLeadResponse(
    val userId: String,
    val fullName: String,
    val email: String,
    val departmentId: String,
    val departmentName: String,
)

fun Route.telegramRoutes(
    linkService: TelegramLinkService,
    ttlMinutes: Long,
) {
    route("/telegram") {
        post("/link-code") {
            val principal = call.principal<UserPrincipal>()!!
            val userId = UUID.fromString(principal.userId)
            val code = linkService.generateCode(userId)
            call.respond(HttpStatusCode.OK, LinkCodeResponse(code = code, ttlMinutes = ttlMinutes))
        }

        get("/status") {
            val principal = call.principal<UserPrincipal>()!!
            val userId = UUID.fromString(principal.userId)
            val linked = linkService.isLinked(userId)
            val pending = if (!linked) linkService.getPendingCode(userId) else null
            call.respond(HttpStatusCode.OK, TelegramStatusResponse(linked = linked, pendingCode = pending))
        }

        delete("/unlink") {
            val principal = call.principal<UserPrincipal>()!!
            val userId = UUID.fromString(principal.userId)
            linkService.unlink(userId)
            call.respond(HttpStatusCode.OK, mapOf("message" to "Telegram unlinked"))
        }
    }
}

fun Route.departmentLeadRoutes(deptLeadRepo: DepartmentLeadRepository) {
    route("/departments/{departmentId}/leads") {

        get {
            val principal = call.principal<UserPrincipal>()!!
            val schema = principal.schema
            if (schema == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No tenant"))
                return@get
            }
            if (!principal.roleEnum.canAccess(Role.CLIENT_ADMIN, Role.TEAM_LEAD)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                return@get
            }

            val deptId = UUID.fromString(call.parameters["departmentId"])
            val leads = deptLeadRepo.listByDepartment(schema, deptId)
            call.respond(HttpStatusCode.OK, leads.map {
                DepartmentLeadResponse(
                    userId = it.userId.toString(),
                    fullName = it.userFullName,
                    email = it.userEmail,
                    departmentId = it.departmentId.toString(),
                    departmentName = it.departmentName,
                )
            })
        }

        post {
            val principal = call.principal<UserPrincipal>()!!
            val schema = principal.schema
            if (schema == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No tenant"))
                return@post
            }
            if (!principal.roleEnum.canAccess(Role.CLIENT_ADMIN)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "CLIENT_ADMIN only"))
                return@post
            }

            val body = call.receive<AssignLeadRequest>()
            val deptId = UUID.fromString(call.parameters["departmentId"])
            val userId = UUID.fromString(body.userId)
            deptLeadRepo.assign(schema, userId, deptId)
            call.respond(HttpStatusCode.Created, mapOf("message" to "Lead assigned"))
        }

        delete("/{userId}") {
            val principal = call.principal<UserPrincipal>()!!
            val schema = principal.schema
            if (schema == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No tenant"))
                return@delete
            }
            if (!principal.roleEnum.canAccess(Role.CLIENT_ADMIN)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "CLIENT_ADMIN only"))
                return@delete
            }

            val deptId = UUID.fromString(call.parameters["departmentId"])
            val userId = UUID.fromString(call.parameters["userId"])
            val removed = deptLeadRepo.remove(schema, userId, deptId)
            if (removed) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Lead removed"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Lead not found"))
            }
        }
    }
}
