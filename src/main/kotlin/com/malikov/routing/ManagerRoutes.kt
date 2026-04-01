package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.config.ForbiddenException
import com.malikov.config.NotFoundException
import com.malikov.service.ManagerService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.managerRoutes(service: ManagerService) {
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
                call.respond(service.list(p.schema!!, params, isActive))
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
    }
}
