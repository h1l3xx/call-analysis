package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.dto.UpsertDepartmentCallPolicyRequest
import com.malikov.service.DepartmentCallPolicyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.departmentCallPolicyRoutes(service: DepartmentCallPolicyService) {
    route("/department-call-policies") {
        get {
            val p = requireTenantRole(Role.CLIENT_ADMIN, Role.TEAM_LEAD)
            call.respond(service.list(p.schema!!))
        }

        post {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val req = call.receive<UpsertDepartmentCallPolicyRequest>()
            val item = service.upsert(p.schema!!, req)
            call.respond(HttpStatusCode.OK, item)
        }

        delete("/departments/{departmentId}/{direction}") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val departmentId = call.parameters["departmentId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "departmentId is required"))
            val direction = call.parameters["direction"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "direction is required"))

            val deleted = service.deleteDepartmentOverride(p.schema!!, departmentId, direction)
            if (deleted) call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            else call.respond(HttpStatusCode.NotFound, mapOf("error" to "Override not found"))
        }

        delete("/pair/{departmentIdA}/{departmentIdB}") {
            val p = requireTenantRole(Role.CLIENT_ADMIN)
            val a = call.parameters["departmentIdA"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "departmentIdA is required"))
            val b = call.parameters["departmentIdB"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "departmentIdB is required"))

            val count = service.deletePairPolicies(p.schema!!, a, b)
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "deleted" to count))
        }
    }
}

