package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.dto.CreateScriptRequest
import com.malikov.dto.UpdateScriptRequest
import com.malikov.service.ScriptService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.scriptRoutes(service: ScriptService) {
    route("/scripts") {

        get {
            val p = requireTenantRole(Role.MANAGER, Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val params = paginationParams()
            val isActive = if (p.roleEnum == Role.MANAGER) true else call.parameters["isActive"]?.toBooleanStrictOrNull()
            call.respond(service.list(p.schema!!, params, isActive))
        }

        post {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val request = call.receive<CreateScriptRequest>()
            val result = service.create(p.schema!!, request)
            call.respond(HttpStatusCode.Created, result)
        }

        get("/{id}") {
            val p = requireTenantRole(Role.MANAGER, Role.TEAM_LEAD, Role.CLIENT_ADMIN)
            val scriptId = pathUuid("id")
            val detail = service.getById(p.schema!!, scriptId)
            if (p.roleEnum == Role.MANAGER && !detail.isActive) {
                throw com.malikov.config.ForbiddenException("Access denied")
            }
            call.respond(detail)
        }

        put("/{id}") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val scriptId = pathUuid("id")
            val request = call.receive<UpdateScriptRequest>()
            call.respond(service.update(p.schema!!, scriptId, request))
        }
    }
}
