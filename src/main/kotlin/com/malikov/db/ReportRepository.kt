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
    val scoredCalls: Long,
    val internalCalls: Long,
    val externalCalls: Long,
    val avgScore: Double?,
    val minScore: Double?,
    val maxScore: Double?,
    val prevAvgScore: Double?,
    val topWeaknesses: List<String>,
)

data class DepartmentStats(
    val departmentId: UUID,
    val departmentName: String,
    val totalCalls: Long,
    val doneCalls: Long,
    val failedCalls: Long,
    val internalCalls: Long,
    val externalCalls: Long,
    val avgScore: Double?,
    val prevAvgScore: Double?,
    val managerCount: Long,
    val lowScoreCalls: Long,
    val managers: List<ManagerStats>,
)

data class TenantStats(
    val totalCalls: Long,
    val doneCalls: Long,
    val failedCalls: Long,
    val internalCalls: Long,
    val externalCalls: Long,
    val avgScore: Double?,
    val prevAvgScore: Double?,
    val departments: List<DepartmentStats>,
)

class ReportRepository {

    fun managerStats(schema: String, managerId: UUID, sinceMs: Long): ManagerStats? = transaction {
        val prevSinceMs = sinceMs - (System.currentTimeMillis() - sinceMs)
        val current = collectManagerRaw(schema, managerId, sinceMs)
            ?: return@transaction null
        val prev = collectManagerRaw(schema, managerId, prevSinceMs, sinceMs)
        current.copy(prevAvgScore = prev?.avgScore)
    }

    private fun collectManagerRaw(
        schema: String,
        managerId: UUID,
        sinceMs: Long,
        untilMs: Long = Long.MAX_VALUE,
    ): ManagerStats? = transaction {
        val cl = TCalls(schema)
        val m = TManagers(schema)
        val d = TDepartments(schema)
        val qs = TQualityScores(schema)

        val perCall = cl.join(m, JoinType.INNER, cl.managerId, m.id)
            .join(Users, JoinType.INNER, m.userId, Users.id)
            .join(d, JoinType.LEFT, m.departmentId, d.id)
            .join(qs, JoinType.LEFT, cl.id, qs.callId)
            .selectAll()
            .where {
                (cl.managerId eq managerId) and
                (cl.createdAt greaterEq sinceMs) and
                (if (untilMs < Long.MAX_VALUE) cl.createdAt less untilMs else Op.TRUE)
            }
            .toList()

        if (perCall.isEmpty()) return@transaction null

        val allScores = mutableListOf<Double>()
        val weaknessMap = mutableMapOf<String, Int>()
        var total = 0L; var done = 0L; var failed = 0L
        var internal = 0L; var external = 0L
        var mName = ""; var deptId: UUID? = null; var deptName: String? = null

        for (row in perCall) {
            total++
            mName = row[Users.fullName]
            deptId = row[m.departmentId]
            deptName = row.getOrNull(d.name)
            when (row[cl.status]) {
                "done" -> done++
                "failed" -> failed++
            }
            when (row[cl.callType]) {
                "internal" -> internal++
                else -> external++
            }
            row[qs.overallScore]?.let { allScores.add(it) }
            row.getOrNull(qs.weaknesses)?.let { raw ->
                parseJsonStringList(raw).forEach { w ->
                    weaknessMap[w] = (weaknessMap[w] ?: 0) + 1
                }
            }
        }

        ManagerStats(
            managerId = managerId,
            managerName = mName,
            departmentId = deptId,
            departmentName = deptName,
            totalCalls = total,
            doneCalls = done,
            failedCalls = failed,
            scoredCalls = allScores.size.toLong(),
            internalCalls = internal,
            externalCalls = external,
            avgScore = allScores.takeIf { it.isNotEmpty() }?.average(),
            minScore = allScores.minOrNull(),
            maxScore = allScores.maxOrNull(),
            prevAvgScore = null,
            topWeaknesses = weaknessMap.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key },
        )
    }

    fun departmentStats(schema: String, departmentId: UUID, sinceMs: Long): DepartmentStats? = transaction {
        val d = TDepartments(schema)
        val m = TManagers(schema)
        val qs = TQualityScores(schema)
        val cl = TCalls(schema)

        val dept = d.selectAll().where { d.id eq departmentId }.singleOrNull()
            ?: return@transaction null
        val deptName = dept[d.name]

        val managers = m.selectAll()
            .where { m.departmentId eq departmentId }
            .map { it[m.id] }

        if (managers.isEmpty()) return@transaction DepartmentStats(
            departmentId = departmentId,
            departmentName = deptName,
            totalCalls = 0, doneCalls = 0, failedCalls = 0,
            internalCalls = 0, externalCalls = 0,
            avgScore = null, prevAvgScore = null,
            managerCount = 0, lowScoreCalls = 0,
            managers = emptyList(),
        )

        val mgrStats = managers.mapNotNull { mgrId ->
            managerStats(schema, mgrId, sinceMs)
        }

        val totalCalls = mgrStats.sumOf { it.totalCalls }
        val doneCalls = mgrStats.sumOf { it.doneCalls }
        val allAvgs = mgrStats.mapNotNull { it.avgScore }
        val prevAvgs = mgrStats.mapNotNull { it.prevAvgScore }

        val lowScoreCalls = countLowScoreCalls(schema, managers, sinceMs)

        DepartmentStats(
            departmentId = departmentId,
            departmentName = deptName,
            totalCalls = totalCalls,
            doneCalls = doneCalls,
            failedCalls = mgrStats.sumOf { it.failedCalls },
            internalCalls = mgrStats.sumOf { it.internalCalls },
            externalCalls = mgrStats.sumOf { it.externalCalls },
            avgScore = allAvgs.takeIf { it.isNotEmpty() }?.average(),
            prevAvgScore = prevAvgs.takeIf { it.isNotEmpty() }?.average(),
            managerCount = mgrStats.size.toLong(),
            lowScoreCalls = lowScoreCalls,
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

        val allAvgs = deptStats.mapNotNull { it.avgScore }
        val prevAvgs = deptStats.mapNotNull { it.prevAvgScore }

        TenantStats(
            totalCalls = deptStats.sumOf { it.totalCalls },
            doneCalls = deptStats.sumOf { it.doneCalls },
            failedCalls = deptStats.sumOf { it.failedCalls },
            internalCalls = deptStats.sumOf { it.internalCalls },
            externalCalls = deptStats.sumOf { it.externalCalls },
            avgScore = allAvgs.takeIf { it.isNotEmpty() }?.average(),
            prevAvgScore = prevAvgs.takeIf { it.isNotEmpty() }?.average(),
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

    fun findUserByTelegramChat(chatId: Long): TenantUserInfo? = transaction {
        Users.join(Tenants, JoinType.INNER, Users.tenantId, Tenants.id)
            .selectAll()
            .where { Users.telegramChatId eq chatId }
            .singleOrNull()
            ?.let { row ->
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

    private fun countLowScoreCalls(
        schema: String,
        managerIds: List<UUID>,
        sinceMs: Long,
    ): Long {
        val cl = TCalls(schema)
        val qs = TQualityScores(schema)
        return cl.join(qs, JoinType.INNER, cl.id, qs.callId)
            .selectAll()
            .where {
                (cl.managerId inList managerIds) and
                (cl.createdAt greaterEq sinceMs) and
                (qs.overallScore less 50.0)
            }
            .count()
    }

    private fun parseJsonStringList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) {
                trimmed.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
            } else listOf(trimmed)
        } catch (_: Exception) { emptyList() }
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
