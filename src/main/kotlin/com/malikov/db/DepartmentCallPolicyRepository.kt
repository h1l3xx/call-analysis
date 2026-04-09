package com.malikov.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class DepartmentCallPolicyRow(
    val id: UUID,
    val departmentId: UUID?,
    val secondDepartmentId: UUID?,
    val callDirection: String,
    val scriptId: UUID?,
    val promptTemplateId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class DepartmentCallPolicyRepository {

    fun list(schema: String): List<DepartmentCallPolicyRow> = transaction {
        val p = TDepartmentCallPolicies(schema)
        p.selectAll()
            .orderBy(p.departmentId, SortOrder.ASC)
            .orderBy(p.callDirection, SortOrder.ASC)
            .map { it.toRow(p) }
    }

    fun upsert(
        schema: String,
        departmentId: UUID?,
        secondDepartmentId: UUID?,
        callDirection: String,
        scriptId: UUID?,
        promptTemplateId: String,
    ): DepartmentCallPolicyRow = transaction {
        val p = TDepartmentCallPolicies(schema)
        val now = System.currentTimeMillis()

        val existing = p.selectAll()
            .where {
                (if (departmentId == null) p.departmentId.isNull() else (p.departmentId eq departmentId)) and
                (if (secondDepartmentId == null) p.secondDepartmentId.isNull() else (p.secondDepartmentId eq secondDepartmentId)) and
                (p.callDirection eq callDirection)
            }
            .singleOrNull()

        if (existing != null) {
            val id = existing[p.id]
            p.update({ p.id eq id }) {
                it[p.scriptId] = scriptId
                it[p.promptTemplateId] = promptTemplateId
                it[p.updatedAt] = now
            }
            return@transaction p.selectAll().where { p.id eq id }.single().toRow(p)
        }

        val id = p.insert {
            it[p.departmentId] = departmentId
            it[p.secondDepartmentId] = secondDepartmentId
            it[p.callDirection] = callDirection
            it[p.scriptId] = scriptId
            it[p.promptTemplateId] = promptTemplateId
            it[p.createdAt] = now
            it[p.updatedAt] = now
        }[p.id]

        p.selectAll().where { p.id eq id }.single().toRow(p)
    }

    fun resolvePolicy(
        schema: String,
        departmentId: UUID?,
        secondDepartmentId: UUID?,
        callDirection: String,
    ): DepartmentCallPolicyRow? = transaction {
        val p = TDepartmentCallPolicies(schema)
        if (departmentId != null && secondDepartmentId != null) {
            p.selectAll()
                .where { (p.departmentId eq departmentId) and (p.secondDepartmentId eq secondDepartmentId) and (p.callDirection eq callDirection) }
                .singleOrNull()
                ?.let { return@transaction it.toRow(p) }
            p.selectAll()
                .where { (p.departmentId eq secondDepartmentId) and (p.secondDepartmentId eq departmentId) and (p.callDirection eq callDirection) }
                .singleOrNull()
                ?.let { return@transaction it.toRow(p) }
        }
        if (departmentId != null) {
            p.selectAll()
                .where { (p.departmentId eq departmentId) and (p.secondDepartmentId.isNull()) and (p.callDirection eq callDirection) }
                .singleOrNull()
                ?.let { return@transaction it.toRow(p) }
        }
        p.selectAll()
            .where { (p.departmentId.isNull()) and (p.secondDepartmentId.isNull()) and (p.callDirection eq callDirection) }
            .singleOrNull()
            ?.toRow(p)
    }

    fun deleteDepartmentOverride(schema: String, departmentId: UUID, callDirection: String): Boolean = transaction {
        val p = TDepartmentCallPolicies(schema)
        p.deleteWhere { (p.departmentId eq departmentId) and (p.secondDepartmentId eq null) and (p.callDirection eq callDirection) } > 0
    }

    fun deletePairPolicies(schema: String, departmentIdA: UUID, departmentIdB: UUID): Int = transaction {
        val p = TDepartmentCallPolicies(schema)
        p.deleteWhere {
            ((p.departmentId eq departmentIdA) and (p.secondDepartmentId eq departmentIdB)) or
            ((p.departmentId eq departmentIdB) and (p.secondDepartmentId eq departmentIdA))
        }
    }

    private fun ResultRow.toRow(p: TDepartmentCallPolicies) = DepartmentCallPolicyRow(
        id = this[p.id],
        departmentId = this[p.departmentId],
        secondDepartmentId = this[p.secondDepartmentId],
        callDirection = this[p.callDirection],
        scriptId = this[p.scriptId],
        promptTemplateId = this[p.promptTemplateId],
        createdAt = this[p.createdAt],
        updatedAt = this[p.updatedAt],
    )
}

