package com.malikov.routing

import com.malikov.dto.CreateTenantRequest
import com.malikov.service.TenantAdminService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.adminRoutes(service: TenantAdminService) {
    route("/tenants") {

        get {
            val params = paginationParams()
            call.respond(service.list(params))
        }

        post {
            val request = call.receive<CreateTenantRequest>()
            val result = service.create(request)
            call.respond(HttpStatusCode.Created, result)
        }

        get("/{id}/usage") {
            val tenantId = pathUuid("id")
            call.respond(service.getUsage(tenantId))
        }
    }
}
