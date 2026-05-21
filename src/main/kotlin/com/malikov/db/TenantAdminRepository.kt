package com.malikov.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private fun ResultRow.toUserRow() = UserRow(
    id           = this[Users.id],
    tenantId     = this[Users.tenantId],
    email        = this[Users.email],
    passwordHash = this[Users.passwordHash],
    fullName     = this[Users.fullName],
    role         = this[Users.role],
    isActive     = this[Users.isActive],
    dbSchema     = null,
    createdAt    = this[Users.createdAt],
    lastActiveAt = this[Users.lastActiveAt],
)

object Plans : Table("public.plans") {
    val id             = uuid("id").autoGenerate()
    val name           = text("name")
    val minutesLimit   = integer("minutes_limit")
    val pricePerMinute = double("price_per_minute")
    val isActive       = bool("is_active").default(true)
    val createdAt      = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

data class TenantRow(
    val id: UUID,
    val slug: String,
    val name: String,
    val dbSchema: String,
    val isActive: Boolean,
    val createdAt: Long,
)

data class TenantUsageRow(
    val tenantId: UUID,
    val tenantName: String,
    val planName: String,
    val minutesUsed: Int,
    val minutesLimit: Int,
    val periodStart: String,
    val periodEnd: String,
)

class TenantAdminRepository {

    fun list(off: Long, limit: Int): Pair<List<TenantRow>, Long> = transaction {
        val total = Tenants.selectAll().count()
        val items = Tenants.selectAll()
            .orderBy(Tenants.createdAt, SortOrder.DESC)
            .limit(limit, off)
            .map { row ->
                TenantRow(
                    id        = row[Tenants.id],
                    slug      = row[Tenants.slug],
                    name      = row[Tenants.name],
                    dbSchema  = row[Tenants.dbSchema],
                    isActive  = row[Tenants.isActive],
                    createdAt = row[Tenants.createdAt],
                )
            }
        items to total
    }

    fun create(slug: String, name: String, planId: UUID): TenantRow = transaction {
        val dbSchema = "tenant_${slug.replace("-", "_")}"
        val now = System.currentTimeMillis()

        val tenantId = Tenants.insert {
            it[Tenants.slug]      = slug
            it[Tenants.name]      = name
            it[Tenants.dbSchema]  = dbSchema
            it[Tenants.createdAt] = now
        }[Tenants.id]

        // Вызываем SQL-функцию создания схемы
        exec("SELECT public.create_tenant_schema('$dbSchema')")
        exec("SELECT public.ensure_tenant_prompt_template_catalog('$dbSchema')")
        exec("SELECT public.ensure_tenant_policy_structures('$dbSchema')")
        exec("SELECT public.ensure_tenant_directional_prompt_templates('$dbSchema')")
        exec("SELECT public.ensure_tenant_internal_direction_defaults('$dbSchema')")

        // Выдаём права
        exec("GRANT ALL ON SCHEMA $dbSchema TO malikov_app")
        exec("GRANT ALL ON ALL TABLES IN SCHEMA $dbSchema TO malikov_app")
        exec("GRANT ALL ON ALL SEQUENCES IN SCHEMA $dbSchema TO malikov_app")

        // Создаём подписку
        TenantSubscriptions.insert {
            it[TenantSubscriptions.tenantId]    = tenantId
            it[TenantSubscriptions.planId]      = planId
            it[TenantSubscriptions.periodStart] = java.time.LocalDate.now().withDayOfMonth(1).toString()
            it[TenantSubscriptions.periodEnd]   = java.time.LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1).toString()
        }

        TenantRow(tenantId, slug, name, dbSchema, true, now)
    }

    fun createAdminUser(
        tenantId: UUID,
        email: String,
        passwordHash: String,
        fullName: String,
    ): UUID = transaction {
        Users.insert {
            it[Users.tenantId]     = tenantId
            it[Users.email]        = email.lowercase()
            it[Users.passwordHash] = passwordHash
            it[Users.fullName]     = fullName
            it[Users.role]         = "CLIENT_ADMIN"
            it[Users.createdAt]    = System.currentTimeMillis()
            it[Users.updatedAt]    = System.currentTimeMillis()
        }[Users.id]
    }

    fun listUsers(tenantId: UUID): List<UserRow> = transaction {
        Users
            .selectAll()
            .where { Users.tenantId eq tenantId }
            .orderBy(Users.lastActiveAt, SortOrder.DESC_NULLS_LAST)
            .map { it.toUserRow() }
    }

    fun getUsage(tenantId: UUID): TenantUsageRow? = transaction {
        val result = Tenants
            .join(TenantSubscriptions, JoinType.INNER, Tenants.id, TenantSubscriptions.tenantId)
            .join(Plans, JoinType.INNER, TenantSubscriptions.planId, Plans.id)
            .selectAll()
            .where { (Tenants.id eq tenantId) and (TenantSubscriptions.isCurrent eq true) }
            .singleOrNull() ?: return@transaction null

        TenantUsageRow(
            tenantId     = result[Tenants.id],
            tenantName   = result[Tenants.name],
            planName     = result[Plans.name],
            minutesUsed  = result[TenantSubscriptions.minutesUsed],
            minutesLimit = result[Plans.minutesLimit],
            periodStart  = result[TenantSubscriptions.periodStart],
            periodEnd    = result[TenantSubscriptions.periodEnd],
        )
    }
}
