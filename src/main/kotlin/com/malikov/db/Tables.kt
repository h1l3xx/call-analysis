package com.malikov.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object Users : Table("public.users") {
    val id             = uuid("id").autoGenerate()
    val tenantId       = uuid("tenant_id").nullable()
    val email          = text("email")
    val passwordHash   = text("password_hash")
    val fullName       = text("full_name")
    val role           = text("role")
    val isActive       = bool("is_active").default(true)
    val telegramChatId = long("telegram_chat_id").nullable()
    val createdAt      = long("created_at")
    val updatedAt      = long("updated_at")
    val lastActiveAt   = long("last_active_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object Tenants : Table("public.tenants") {
    val id        = uuid("id").autoGenerate()
    val slug      = text("slug")
    val name      = text("name")
    val dbSchema  = text("db_schema")
    val isActive  = bool("is_active").default(true)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

object RefreshTokens : Table("public.refresh_tokens") {
    val tokenHash  = text("token_hash")
    val userId     = uuid("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
    val deviceInfo = text("device_info").nullable()
    val ipAddress  = text("ip_address").nullable()
    val expiresAt  = long("expires_at")
    val createdAt  = long("created_at")
    val revokedAt  = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(tokenHash)
}

object TenantSubscriptions : Table("public.tenant_subscriptions") {
    val id          = uuid("id").autoGenerate()
    val tenantId    = uuid("tenant_id").references(Tenants.id, onDelete = ReferenceOption.CASCADE)
    val planId      = uuid("plan_id")
    val minutesUsed = integer("minutes_used").default(0)
    val periodStart = text("period_start")
    val periodEnd   = text("period_end")
    val isCurrent   = bool("is_current").default(true)

    override val primaryKey = PrimaryKey(id)
}