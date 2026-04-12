package com.malikov.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class ManagerPhoneRow(
    val id: UUID,
    val managerId: UUID,
    val phoneNumber: String,
    val label: String?,
    val isPrimary: Boolean,
)

data class ManagerRow(
    val id: UUID,
    val userId: UUID,
    val fullName: String,
    val email: String,
    val departmentId: UUID?,
    val departmentName: String?,
    val extension: String?,
    val phoneNumber: String?,          // kept for backward compat; = primary phone
    val phoneNumbers: List<ManagerPhoneRow> = emptyList(),
    val isActive: Boolean,
    val createdAt: Long,
)

class ManagerRepository {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun allPhonesForManagers(schema: String, managerIds: Collection<UUID>): Map<UUID, List<ManagerPhoneRow>> {
        if (managerIds.isEmpty()) return emptyMap()
        val p = TManagerPhoneNumbers(schema)
        return p.selectAll()
            .where { p.managerId inList managerIds }
            .orderBy(p.isPrimary to SortOrder.DESC, p.createdAt to SortOrder.ASC)
            .groupBy({ it[p.managerId] }) { row ->
                ManagerPhoneRow(
                    id          = row[p.id],
                    managerId   = row[p.managerId],
                    phoneNumber = row[p.phoneNumber],
                    label       = row[p.label],
                    isPrimary   = row[p.isPrimary],
                )
            }
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

    // ─────────────────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────────────────

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
        val rows = query
            .orderBy(Users.fullName)
            .limit(limit, off)
            .map { it.toManagerRow(m, d) }

        val phones = allPhonesForManagers(schema, rows.map { it.id })
        val items = rows.map { it.copy(phoneNumbers = phones[it.id] ?: emptyList()) }
        items to total
    }

    fun findById(schema: String, managerId: UUID): ManagerRow? = transaction {
        val m = TManagers(schema)
        val d = TDepartments(schema)

        val row = m.join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .selectAll()
            .where { m.id eq managerId }
            .singleOrNull()
            ?.toManagerRow(m, d) ?: return@transaction null

        val phones = allPhonesForManagers(schema, listOf(row.id))
        row.copy(phoneNumbers = phones[row.id] ?: emptyList())
    }

    fun findByUserId(schema: String, userId: UUID): ManagerRow? = transaction {
        val m = TManagers(schema)
        val d = TDepartments(schema)

        val row = m.join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .selectAll()
            .where { m.userId eq userId }
            .singleOrNull()
            ?.toManagerRow(m, d) ?: return@transaction null

        val phones = allPhonesForManagers(schema, listOf(row.id))
        row.copy(phoneNumbers = phones[row.id] ?: emptyList())
    }

    fun findByPhone(schema: String, phone: String): ManagerRow? = transaction {
        val m = TManagers(schema)
        val d = TDepartments(schema)
        val p = TManagerPhoneNumbers(schema)

        val normalized = phone.replace(Regex("[^0-9]"), "")

        // Search in managers.phone_number / extension AND manager_phone_numbers
        val managerIdFromExtra = p.select(p.managerId)
            .where { (p.phoneNumber eq normalized) or (p.phoneNumber eq phone) }
            .firstOrNull()?.get(p.managerId)

        val row = m.join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .selectAll()
            .where {
                (m.phoneNumber eq normalized) or (m.extension eq normalized) or
                (m.phoneNumber eq phone) or (m.extension eq phone) or
                (if (managerIdFromExtra != null) (m.id eq managerIdFromExtra) else Op.FALSE)
            }
            .firstOrNull()
            ?.toManagerRow(m, d) ?: return@transaction null

        val phones = allPhonesForManagers(schema, listOf(row.id))
        row.copy(phoneNumbers = phones[row.id] ?: emptyList())
    }

    /** Все менеджеры, чей extension входит в список. */
    fun findAllByExtensions(schema: String, extensions: List<String>): List<ManagerRow> = transaction {
        val m = TManagers(schema)
        val d = TDepartments(schema)
        if (extensions.isEmpty()) return@transaction emptyList()
        val rows = m.join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .selectAll()
            .where { m.extension inList extensions }
            .map { it.toManagerRow(m, d) }
        val phones = allPhonesForManagers(schema, rows.map { it.id })
        rows.map { it.copy(phoneNumbers = phones[it.id] ?: emptyList()) }
    }

    /** Первый найденный менеджер по списку кандидатов (порядок важен). */
    fun findFirstByIdentifiers(schema: String, candidates: List<String>): Pair<String, ManagerRow>? =
        transaction {
            val m = TManagers(schema)
            val d = TDepartments(schema)
            val p = TManagerPhoneNumbers(schema)

            for (c in candidates) {
                val normalized = c.replace(Regex("[^0-9]"), "")

                // Check extra phones table first (covers additional numbers)
                val extraManagerId = p.select(p.managerId)
                    .where { (p.phoneNumber eq normalized) or (p.phoneNumber eq c) }
                    .firstOrNull()?.get(p.managerId)

                val row = m.join(d, JoinType.LEFT, m.departmentId, d.id)
                    .join(Users, JoinType.INNER, m.userId, Users.id)
                    .selectAll()
                    .where {
                        (m.phoneNumber eq normalized) or (m.extension eq normalized) or
                        (m.phoneNumber eq c) or (m.extension eq c) or
                        (if (extraManagerId != null) (m.id eq extraManagerId) else Op.FALSE)
                    }
                    .firstOrNull()
                    ?.toManagerRow(m, d)

                if (row != null) {
                    val phones = allPhonesForManagers(schema, listOf(row.id))
                    return@transaction c to row.copy(phoneNumbers = phones[row.id] ?: emptyList())
                }
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

    // ─────────────────────────────────────────────────────────────────────────
    // Phone number CRUD
    // ─────────────────────────────────────────────────────────────────────────

    fun addPhone(schema: String, managerId: UUID, phoneNumber: String, label: String?, isPrimary: Boolean): ManagerPhoneRow = transaction {
        val p = TManagerPhoneNumbers(schema)
        val normalized = phoneNumber.replace(Regex("[^0-9]"), "").ifBlank { phoneNumber }
        val now = System.currentTimeMillis()

        // If setting as primary, unset existing primary
        if (isPrimary) {
            p.update({ p.managerId eq managerId }) { it[p.isPrimary] = false }
        }

        val id = p.insert {
            it[p.managerId]   = managerId
            it[p.phoneNumber] = normalized
            it[p.label]       = label
            it[p.isPrimary]   = isPrimary
            it[p.createdAt]   = now
        }[p.id]

        // Sync primary back to managers.phone_number
        if (isPrimary) syncPrimaryPhone(schema, managerId, normalized)

        ManagerPhoneRow(id = id, managerId = managerId, phoneNumber = normalized, label = label, isPrimary = isPrimary)
    }

    fun removePhone(schema: String, phoneId: UUID): Boolean = transaction {
        val p = TManagerPhoneNumbers(schema)
        val row = p.selectAll().where { p.id eq phoneId }.singleOrNull() ?: return@transaction false
        val managerId = row[p.managerId]
        val wasPrimary = row[p.isPrimary]

        p.deleteWhere { p.id eq phoneId }

        // If we deleted the primary, promote the next one
        if (wasPrimary) {
            val next = p.selectAll()
                .where { p.managerId eq managerId }
                .orderBy(p.createdAt)
                .firstOrNull()
            if (next != null) {
                p.update({ p.id eq next[p.id] }) { it[p.isPrimary] = true }
                syncPrimaryPhone(schema, managerId, next[p.phoneNumber])
            } else {
                syncPrimaryPhone(schema, managerId, null)
            }
        }
        true
    }

    fun listPhones(schema: String, managerId: UUID): List<ManagerPhoneRow> = transaction {
        val p = TManagerPhoneNumbers(schema)
        p.selectAll()
            .where { p.managerId eq managerId }
            .orderBy(p.isPrimary to SortOrder.DESC, p.createdAt to SortOrder.ASC)
            .map { ManagerPhoneRow(it[p.id], it[p.managerId], it[p.phoneNumber], it[p.label], it[p.isPrimary]) }
    }

    private fun syncPrimaryPhone(schema: String, managerId: UUID, phone: String?) {
        val m = TManagers(schema)
        m.update({ m.id eq managerId }) { it[m.phoneNumber] = phone }
    }
}
