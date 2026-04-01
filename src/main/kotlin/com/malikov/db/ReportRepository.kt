package com.malikov.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class ManagerStats(
    val managerId: UUID,
    val managerName: String,
    val departmentId: UUID?,
    val departmentName: String?,
    val totalCalls: Long,
    val doneCalls: Long,
    val failedCalls: Long,
    val avgScore: Double?,
    val minScore: Double?,
    val maxScore: Double?,
)

data class DepartmentStats(
    val departmentId: UUID,
    val departmentName: String,
    val totalCalls: Long,
    val doneCalls: Long,
    val avgScore: Double?,
    val managerCount: Long,
    val managers: List<ManagerStats>,
)

data class TenantStats(
    val totalCalls: Long,
    val doneCalls: Long,
    val failedCalls: Long,
    val avgScore: Double?,
    val departments: List<DepartmentStats>,
)

class ReportRepository {

    fun managerStats(schema: String, managerId: UUID, sinceMs: Long): ManagerStats? = transaction {
        val cl = TCalls(schema)
        val m = TManagers(schema)
        val d = TDepartments(schema)
        val qs = TQualityScores(schema)

        val base = cl.join(m, JoinType.INNER, cl.managerId, m.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(qs, JoinType.LEFT, cl.id, qs.callId)

        val rows = base.select(
            m.id,
            Users.fullName,
            m.departmentId,
            d.name,
            cl.id.count(),
            cl.status,
            qs.overallScore,
        ).where {
            (cl.managerId eq managerId) and (cl.createdAt greaterEq sinceMs)
        }.groupBy(m.id, Users.fullName, m.departmentId, d.name, cl.status, qs.overallScore)
            .toList()

        if (rows.isEmpty()) return@transaction null

        val allScores = mutableListOf<Double>()
        var total = 0L
        var done = 0L
        var failed = 0L
        var mName = ""
        var deptId: UUID? = null
        var deptName: String? = null

        val perCall = cl.join(m, JoinType.INNER, cl.managerId, m.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(qs, JoinType.LEFT, cl.id, qs.callId)
            .selectAll()
            .where { (cl.managerId eq managerId) and (cl.createdAt greaterEq sinceMs) }

        for (row in perCall) {
            total++
            mName = row[Users.fullName]
            deptId = row[m.departmentId]
            deptName = row.getOrNull(d.name)
            when (row[cl.status]) {
                "done" -> done++
                "failed" -> failed++
            }
            row[qs.overallScore]?.let { allScores.add(it) }
        }

        ManagerStats(
            managerId = managerId,
            managerName = mName,
            departmentId = deptId,
            departmentName = deptName,
            totalCalls = total,
            doneCalls = done,
            failedCalls = failed,
            avgScore = allScores.takeIf { it.isNotEmpty() }?.average(),
            minScore = allScores.minOrNull(),
            maxScore = allScores.maxOrNull(),
        )
    }

    fun departmentStats(schema: String, departmentId: UUID, sinceMs: Long): DepartmentStats? = transaction {
        val cl = TCalls(schema)
        val m = TManagers(schema)
        val d = TDepartments(schema)
        val qs = TQualityScores(schema)

        val dept = d.selectAll().where { d.id eq departmentId }.singleOrNull()
            ?: return@transaction null
        val deptName = dept[d.name]

        val managers = m.selectAll()
            .where { m.departmentId eq departmentId }
            .map { it[m.id] }

        if (managers.isEmpty()) return@transaction DepartmentStats(
            departmentId = departmentId,
            departmentName = deptName,
            totalCalls = 0,
            doneCalls = 0,
            avgScore = null,
            managerCount = 0,
            managers = emptyList(),
        )

        val mgrStats = managers.mapNotNull { mgrId ->
            managerStats(schema, mgrId, sinceMs)
        }

        val totalCalls = mgrStats.sumOf { it.totalCalls }
        val doneCalls = mgrStats.sumOf { it.doneCalls }
        val allAvgs = mgrStats.mapNotNull { it.avgScore }

        DepartmentStats(
            departmentId = departmentId,
            departmentName = deptName,
            totalCalls = totalCalls,
            doneCalls = doneCalls,
            avgScore = allAvgs.takeIf { it.isNotEmpty() }?.average(),
            managerCount = mgrStats.size.toLong(),
            managers = mgrStats.sortedByDescending { it.avgScore ?: 0.0 },
        )
    }

    fun tenantStats(schema: String, sinceMs: Long): TenantStats = transaction {
        val d = TDepartments(schema)

        val departments = d.selectAll()
            .where { d.isActive eq true }
            .map { it[d.id] }

        val deptStats = departments.mapNotNull { deptId ->
            departmentStats(schema, deptId, sinceMs)
        }

        TenantStats(
            totalCalls = deptStats.sumOf { it.totalCalls },
            doneCalls = deptStats.sumOf { it.doneCalls },
            failedCalls = deptStats.sumOf { ds -> ds.managers.sumOf { it.failedCalls } },
            avgScore = deptStats.mapNotNull { it.avgScore }.takeIf { it.isNotEmpty() }?.average(),
            departments = deptStats.sortedByDescending { it.avgScore ?: 0.0 },
        )
    }

    fun allTenantsWithLinkedUsers(): List<TenantUserInfo> = transaction {
        Users.join(Tenants, JoinType.INNER, Users.tenantId, Tenants.id)
            .selectAll()
            .where { Users.telegramChatId.isNotNull() and Users.isActive.eq(true) }
            .map { row ->
                TenantUserInfo(
                    userId = row[Users.id],
                    tenantId = row[Users.tenantId]!!,
                    schema = row[Tenants.dbSchema],
                    role = row[Users.role],
                    fullName = row[Users.fullName],
                    chatId = row[Users.telegramChatId]!!,
                )
            }
    }
}

data class TenantUserInfo(
    val userId: UUID,
    val tenantId: UUID,
    val schema: String,
    val role: String,
    val fullName: String,
    val chatId: Long,
)
