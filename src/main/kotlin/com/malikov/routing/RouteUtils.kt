package com.malikov.routing

import com.malikov.auth.Role
import com.malikov.auth.UserPrincipal
import com.malikov.config.ForbiddenException
import com.malikov.dto.PaginationParams
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.util.pipeline.*
import java.util.UUID

fun PipelineContext<Unit, ApplicationCall>.principal(): UserPrincipal =
    call.principal<UserPrincipal>()!!

fun PipelineContext<Unit, ApplicationCall>.requireRole(vararg roles: Role): UserPrincipal {
    val p = principal()
    if (p.roleEnum !in roles) throw ForbiddenException("Insufficient permissions")
    return p
}

fun PipelineContext<Unit, ApplicationCall>.requireTenant(): UserPrincipal {
    val p = principal()
    if (p.schema == null) throw ForbiddenException("Tenant-scoped endpoint")
    return p
}

fun PipelineContext<Unit, ApplicationCall>.requireTenantRole(vararg roles: Role): UserPrincipal {
    val p = requireTenant()
    if (p.roleEnum !in roles) throw ForbiddenException("Insufficient permissions")
    return p
}

fun PipelineContext<Unit, ApplicationCall>.paginationParams(): PaginationParams =
    PaginationParams.from(
        page     = call.parameters["page"]?.toIntOrNull(),
        pageSize = call.parameters["pageSize"]?.toIntOrNull(),
    )

fun PipelineContext<Unit, ApplicationCall>.pathUuid(name: String): UUID =
    UUID.fromString(call.parameters[name] ?: throw IllegalArgumentException("Missing path parameter: $name"))
