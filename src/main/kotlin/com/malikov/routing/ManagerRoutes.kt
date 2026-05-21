package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.config.ForbiddenException
import com.malikov.config.NotFoundException
import com.malikov.db.UserRepository
import com.malikov.dto.AddPhoneRequest
import com.malikov.dto.UserSearchResponse
import com.malikov.service.ManagerEvaluationService
import com.malikov.service.ManagerService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.managerRoutes(service: ManagerService, evaluationService: ManagerEvaluationService? = null, userRepository: UserRepository? = null) {
    // ── User search (для autocomplete в назначении тимлидов и т.п.) ──────────
    route("/users") {
        get("/search") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val q = call.parameters["q"]?.trim() ?: ""
            val role = call.parameters["role"]
            if (q.length < 2) {
                call.respond(emptyList<UserSearchResponse>())
                return@get
            }
            val repo = userRepository ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "User repository unavailable"))
                return@get
            }
            val tenantId = UUID.fromString(p.tenantId ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Tenant not found"))
                return@get
            })
            val results = repo.searchByTenant(tenantId, q, role)
                .map { UserSearchResponse(it.id.toString(), it.fullName, it.email, it.role) }
            call.respond(results)
        }
    }

    route("/managers") {

        get {
            val p = requireTenantRole(Role.MANAGER, Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val params = paginationParams()

            if (p.roleEnum == Role.MANAGER) {
                val manager = service.getByUserId(p.schema!!, UUID.fromString(p.userId))
                    ?: throw NotFoundException("Manager profile not found")
                call.respond(listOf(manager))
            } else {
                val isActive = call.parameters["isActive"]?.toBooleanStrictOrNull()
                val search = call.parameters["search"]?.takeIf { it.isNotBlank() }
                val departmentId = call.parameters["departmentId"]
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                call.respond(service.list(p.schema!!, params, isActive, search, departmentId))
            }
        }

        get("/{id}") {
            val p = requireTenantRole(Role.MANAGER, Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val managerId = pathUuid("id")

            if (p.roleEnum == Role.MANAGER) {
                val myManager = service.getByUserId(p.schema!!, UUID.fromString(p.userId))
                    ?: throw NotFoundException("Manager profile not found")
                if (myManager.id != managerId.toString()) throw ForbiddenException("Access denied")
                call.respond(myManager)
            } else {
                call.respond(service.getById(p.schema!!, managerId))
            }
        }

        // ── Phone numbers ───────────────────────────────────────────────────

        get("/{id}/phones") {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val managerId = pathUuid("id")
            call.respond(service.listPhones(p.schema!!, managerId))
        }

        post("/{id}/phones") {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val managerId = pathUuid("id")
            val req = call.receive<AddPhoneRequest>()
            val phone = service.addPhone(p.schema!!, managerId, req)
            call.respond(HttpStatusCode.Created, phone)
        }

        delete("/{id}/phones/{phoneId}") {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val managerId = pathUuid("id")
            val phoneId = pathUuid("phoneId")
            service.removePhone(p.schema!!, managerId, phoneId)
            call.respond(HttpStatusCode.NoContent)
        }

        // ── Period evaluations ──────────────────────────────────────────────

        get("/{id}/evaluations") {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val managerId = pathUuid("id")
            val svc = evaluationService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Evaluation service unavailable"))
                return@get
            }
            call.respond(svc.list(p.schema!!, managerId))
        }

        post("/{id}/evaluate") {
            val p = requireTenantRole(Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val managerId = pathUuid("id")
            val since = call.parameters["since"]?.toLongOrNull()
            val until = call.parameters["until"]?.toLongOrNull()
            val templateId = call.parameters["templateId"]
            val svc = evaluationService ?: run {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Evaluation service unavailable"))
                return@post
            }
            call.respond(HttpStatusCode.Created, svc.generate(p.schema!!, managerId, since, until, templateId))
        }
    }
}
