package com.malikov.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class ManagerRow(
    val id: UUID,
    val userId: UUID,
    val fullName: String,
    val email: String,
    val departmentId: UUID?,
    val departmentName: String?,
    val extension: String?,
    val phoneNumber: String?,
    val isActive: Boolean,
    val createdAt: Long,
)

class ManagerRepository {

    fun list(
        schema: String,
        off: Long,
        limit: Int,
        isActive: Boolean? = null,
    ): Pair<List<ManagerRow>, Long> = transaction {
        val m = TManagers(schema)
        val d = TDepartments(schema)

        val base = m
            .join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)

        val query = base.selectAll().let { q ->
            isActive?.let { active -> q.where { m.isActive eq active } } ?: q
        }

        val total = query.count()
        val items = query
            .orderBy(Users.fullName)
            .limit(limit, off)
            .map { row -> row.toManagerRow(m, d) }

        items to total
    }

    fun findById(schema: String, managerId: UUID): ManagerRow? = transaction {
        val m = TManagers(schema)
        val d = TDepartments(schema)

        m.join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .selectAll()
            .where { m.id eq managerId }
            .singleOrNull()
            ?.toManagerRow(m, d)
    }

    fun findByUserId(schema: String, userId: UUID): ManagerRow? = transaction {
        val m = TManagers(schema)
        val d = TDepartments(schema)

        m.join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .selectAll()
            .where { m.userId eq userId }
            .singleOrNull()
            ?.toManagerRow(m, d)
    }

    fun findByPhone(schema: String, phone: String): ManagerRow? = transaction {
        val m = TManagers(schema)
        val d = TDepartments(schema)

        val normalized = phone.replace(Regex("[^0-9]"), "")

        m.join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .selectAll()
            .where {
                (m.phoneNumber eq normalized) or (m.extension eq normalized) or
                (m.phoneNumber eq phone) or (m.extension eq phone)
            }
            .firstOrNull()
            ?.toManagerRow(m, d)
    }

    /** Все менеджеры, чей extension входит в список. Для внутренних звонков — оба участника. */
    fun findAllByExtensions(schema: String, extensions: List<String>): List<ManagerRow> = transaction {
        val m = TManagers(schema)
        val d = TDepartments(schema)
        if (extensions.isEmpty()) return@transaction emptyList()
        m.join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .selectAll()
            .where { m.extension inList extensions }
            .map { it.toManagerRow(m, d) }
    }

    /** Первый найденный менеджер по списку кандидатов (порядок важен для внутренних звонков). */
    fun findFirstByIdentifiers(schema: String, candidates: List<String>): Pair<String, ManagerRow>? =
        transaction {
            val m = TManagers(schema)
            val d = TDepartments(schema)
            for (c in candidates) {
                val normalized = c.replace(Regex("[^0-9]"), "")
                val row = m.join(d, JoinType.LEFT, m.departmentId, d.id)
                    .join(Users, JoinType.INNER, m.userId, Users.id)
                    .selectAll()
                    .where {
                        (m.phoneNumber eq normalized) or (m.extension eq normalized) or
                        (m.phoneNumber eq c) or (m.extension eq c)
                    }
                    .firstOrNull()
                    ?.toManagerRow(m, d)
                if (row != null) return@transaction c to row
            }
            null
        }

    /**
     * For a batch of manager IDs, returns a mapping: managerId → list of ALL full names
     * that share the same extension. Only entries with >1 name are included (shared extensions).
     */
    fun findSharedExtensionNames(schema: String, managerIds: List<UUID>): Map<UUID, List<String>> = transaction {
        if (managerIds.isEmpty()) return@transaction emptyMap()
        val m = TManagers(schema)

        val extensionById = m.select(m.id, m.extension)
            .where { m.id inList managerIds }
            .associate { it[m.id] to it[m.extension] }

        val extensions = extensionById.values.filterNotNull().distinct()
        if (extensions.isEmpty()) return@transaction emptyMap()

        val namesByExt: Map<String, List<String>> = m
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .select(m.extension, Users.fullName)
            .where { m.extension inList extensions }
            .groupBy({ it[m.extension]!! }) { it[Users.fullName] }

        val result = mutableMapOf<UUID, List<String>>()
        for ((mgrId, ext) in extensionById) {
            val names = ext?.let { namesByExt[it] } ?: continue
            if (names.size > 1) result[mgrId] = names
        }
        result
    }

    private fun ResultRow.toManagerRow(m: TManagers, d: TDepartments) = ManagerRow(
        id             = this[m.id],
        userId         = this[m.userId],
        fullName       = this[Users.fullName],
        email          = this[Users.email],
        departmentId   = this[m.departmentId],
        departmentName = this.getOrNull(d.name),
        extension      = this[m.extension],
        phoneNumber    = this[m.phoneNumber],
        isActive       = this[m.isActive],
        createdAt      = this[m.createdAt],
    )
}
