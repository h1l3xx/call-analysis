package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.dto.UpsertDepartmentCallPolicyRequest
import com.malikov.service.DepartmentCallPolicyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
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
    }
}

