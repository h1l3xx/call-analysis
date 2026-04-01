package com.malikov.auth

import io.ktor.server.auth.*

enum class Role {
    MANAGER,
    TEAM_LEAD,
    CLIENT_ADMIN,
    SUPERADMIN;

    fun canAccess(vararg allowed: Role) = this in allowed
}

data class UserPrincipal(
    val userId:   String,
    val tenantId: String?,   // null для SUPERADMIN
    val role:     String,
    val schema:   String?,   // "tenant_dev_clinic" — для SET search_path
) : Principal {
    val roleEnum: Role get() = Role.valueOf(role)
    val isSuperAdmin: Boolean get() = roleEnum == Role.SUPERADMIN
}
