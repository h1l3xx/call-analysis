package com.malikov.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class DepartmentLeadRow(
    val userId: UUID,
    val departmentId: UUID,
    val departmentName: String,
    val userFullName: String,
    val userEmail: String,
)

class DepartmentLeadRepository {

    fun listAll(schema: String): List<DepartmentLeadRow> = transaction {
        val dl = TDepartmentLeads(schema)
        val d = TDepartments(schema)
        dl.join(Users, JoinType.INNER, dl.userId, Users.id)
            .join(d, JoinType.INNER, dl.departmentId, d.id)
            .selectAll()
            .orderBy(d.name)
            .map { it.toRow(dl, d) }
    }

    fun listByDepartment(schema: String, departmentId: UUID): List<DepartmentLeadRow> = transaction {
        val dl = TDepartmentLeads(schema)
        val d = TDepartments(schema)

        dl.join(Users, JoinType.INNER, dl.userId, Users.id)
            .join(d, JoinType.INNER, dl.departmentId, d.id)
            .selectAll()
            .where { dl.departmentId eq departmentId }
            .map { it.toRow(dl, d) }
    }

    fun listByUser(schema: String, userId: UUID): List<DepartmentLeadRow> = transaction {
        val dl = TDepartmentLeads(schema)
        val d = TDepartments(schema)

        dl.join(Users, JoinType.INNER, dl.userId, Users.id)
            .join(d, JoinType.INNER, dl.departmentId, d.id)
            .selectAll()
            .where { dl.userId eq userId }
            .map { it.toRow(dl, d) }
    }

    fun assign(schema: String, userId: UUID, departmentId: UUID) = transaction {
        val dl = TDepartmentLeads(schema)
        dl.insertIgnore {
            it[dl.userId] = userId
            it[dl.departmentId] = departmentId
            it[dl.createdAt] = System.currentTimeMillis()
        }
    }

    fun remove(schema: String, userId: UUID, departmentId: UUID): Boolean = transaction {
        val dl = TDepartmentLeads(schema)
        dl.deleteWhere {
            (dl.userId eq userId) and (dl.departmentId eq departmentId)
        } > 0
    }

    private fun ResultRow.toRow(dl: TDepartmentLeads, d: TDepartments) = DepartmentLeadRow(
        userId         = this[dl.userId],
        departmentId   = this[dl.departmentId],
        departmentName = this[d.name],
        userFullName   = this[Users.fullName],
        userEmail      = this[Users.email],
    )
}
