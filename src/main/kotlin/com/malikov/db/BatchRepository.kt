package com.malikov.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

@Serializable
data class CallTypeStatsJson(
    val internal: Int = 0,
    val externalIncoming: Int = 0,
    val externalOutgoing: Int = 0,
    val unknown: Int = 0,
)

data class BatchRow(
    val id: UUID,
    val status: String,
    val totalCalls: Int,
    val processedCalls: Int,
    val callTypeStats: String?,
    val noSpeechCount: Int = 0,
    val failedCount: Int = 0,
    val transcribedOnlyCount: Int = 0,
    val createdAt: Long,
    val finishedAt: Long?,
)

data class BatchSummaryRow(
    val id: UUID,
    val batchId: UUID,
    val scope: String,
    val periodType: String,
    val content: String?,
    val createdAt: Long,
)

class BatchRepository {
    private val json = Json { encodeDefaults = true }

    fun create(schema: String, totalCalls: Int, callTypeStats: CallTypeStatsJson): UUID = transaction {
        val b = TBatches(schema)
        b.insert {
            it[b.status] = "uploading"
            it[b.totalCalls] = totalCalls
            it[b.processedCalls] = 0
            it[b.callTypeStats] = json.encodeToString(callTypeStats)
            it[b.createdAt] = System.currentTimeMillis()
        }[b.id]
    }

    private fun countCallsByStatus(schema: String, batchId: UUID): Map<String, Int> = transaction {
        val cl = TCalls(schema)
        cl.select(cl.status, cl.status.count())
            .where { cl.batchId eq batchId }
            .groupBy(cl.status)
            .associate { it[cl.status] to it[cl.status.count()].toInt() }
    }

    fun findById(schema: String, batchId: UUID): BatchRow? = transaction {
        val b = TBatches(schema)
        val row = b.selectAll().where { b.id eq batchId }.singleOrNull() ?: return@transaction null
        val statusCounts = countCallsByStatus(schema, batchId)
        row.toBatchRow(b, statusCounts)
    }

    fun list(schema: String, off: Long, limit: Int): Pair<List<BatchRow>, Long> = transaction {
        val b = TBatches(schema)
        val total = b.selectAll().count()
        val items = b.selectAll()
            .orderBy(b.createdAt, SortOrder.DESC)
            .limit(limit, off)
            .map { it.toBatchRow(b, emptyMap()) }
        items to total
    }

    fun updateStatus(schema: String, batchId: UUID, status: String) = transaction {
        val b = TBatches(schema)
        b.update({ b.id eq batchId }) {
            it[b.status] = status
            if (status == "done" || status == "failed") {
                it[b.finishedAt] = System.currentTimeMillis()
            }
            // При завершении выравниваем processedCalls = totalCalls —
            // страхуемся от счётчиков, потерянных при двойных сбоях.
            if (status == "done") {
                with(SqlExpressionBuilder) { it[b.processedCalls] = b.totalCalls }
            }
        }
    }

    fun updateTotalCalls(schema: String, batchId: UUID, total: Int) = transaction {
        val b = TBatches(schema)
        b.update({ b.id eq batchId }) { it[b.totalCalls] = total }
    }

    fun addToTotalCalls(schema: String, batchId: UUID, count: Int) = transaction {
        val b = TBatches(schema)
        b.update({ b.id eq batchId }) {
            with(SqlExpressionBuilder) { it[b.totalCalls] = b.totalCalls + count }
        }
    }

    /** Пересчитывает callTypeStats из реальных записей звонков (нужно для чанковых загрузок).
     *  Считаем только "завершённые" звонки (done + transcribed_only + no_speech + failed),
     *  чтобы цифры совпадали с тем, что LLM-summary видит как total_calls.
     */
    fun refreshTypeStats(schema: String, batchId: UUID) = transaction {
        val cl = TCalls(schema)
        // Считаем все завершённые звонки — сумма типов должна совпадать с totalCalls.
        // LLM summary показывает свой total_calls только по done-звонкам — это отдельное число.
        val finishedStatuses = listOf("done", "transcribed_only", "no_speech", "failed")
        val rows = cl.select(cl.callType, cl.callType.count())
            .where { (cl.batchId eq batchId) and (cl.status inList finishedStatuses) }
            .groupBy(cl.callType)
            .associate { (it[cl.callType] ?: "unknown") to it[cl.callType.count()].toInt() }
        val knownSum = (rows["internal"] ?: 0) +
            (rows["external_incoming"] ?: 0) + (rows["external"] ?: 0) +
            (rows["external_outgoing"] ?: 0)
        val totalFinished = rows.values.sum()
        val stats = CallTypeStatsJson(
            internal         = rows["internal"] ?: 0,
            externalIncoming = rows["external_incoming"] ?: (rows["external"] ?: 0),
            externalOutgoing = rows["external_outgoing"] ?: 0,
            unknown          = totalFinished - knownSum,
        )
        val b = TBatches(schema)
        b.update({ b.id eq batchId }) { it[b.callTypeStats] = json.encodeToString(stats) }
    }

    fun deleteById(schema: String, batchId: UUID): Int = transaction {
        val b = TBatches(schema)
        b.deleteWhere { b.id eq batchId }
    }

    fun incrementProcessed(schema: String, batchId: UUID): Int = transaction {
        val b = TBatches(schema)
        b.update({ b.id eq batchId }) {
            with(SqlExpressionBuilder) {
                it[b.processedCalls] = b.processedCalls + 1
            }
        }
        b.selectAll().where { b.id eq batchId }.singleOrNull()?.get(b.processedCalls) ?: 0
    }

    fun listCallsByBatch(
        schema: String,
        batchId: UUID,
        callType: String? = null,
        callIds: List<UUID>? = null,
        off: Long = 0,
        limit: Int = 100,
    ): Pair<List<CallRow>, Long> = transaction {
        val cl = TCalls(schema)
        val m = TManagers(schema)
        val s = TScripts(schema)

        val base = cl
            .join(m, JoinType.LEFT, cl.managerId, m.id)
            .join(Users, JoinType.LEFT, m.userId, Users.id)
            .join(s, JoinType.LEFT, cl.scriptId, s.id)

        val conditions = mutableListOf<Op<Boolean>>(Op.build { cl.batchId eq batchId })
        callType?.let { ct -> conditions.add(Op.build { cl.callType eq ct }) }
        if (!callIds.isNullOrEmpty()) {
            conditions.add(Op.build { cl.id inList callIds })
        }

        val query = base.selectAll().where { conditions.reduce { acc, op -> acc and op } }
        val total = query.count()
        val items = query
            .orderBy(cl.createdAt, SortOrder.DESC)
            .limit(limit, off)
            .map { row ->
                CallRow(
                    id                = row[cl.id],
                    managerId         = row[cl.managerId],
                    managerName       = row.getOrNull(Users.fullName),
                    secondManagerId   = row[cl.secondManagerId],
                    secondManagerName = null,
                    scriptId          = row[cl.scriptId],
                    scriptName        = row.getOrNull(s.name),
                    status            = row[cl.status],
                    source            = row[cl.callSource],
                    batchId           = row[cl.batchId],
                    callType          = row[cl.callType],
                    audioS3Key        = row[cl.audioS3Key],
                    audioFilename     = row[cl.audioFilename],
                    durationSeconds   = row[cl.durationSeconds],
                    failedStep        = row[cl.failedStep],
                    errorMessage      = row[cl.errorMessage],
                    createdAt         = row[cl.createdAt],
                    finishedAt        = row[cl.finishedAt],
                )
            }
        items to total
    }

    fun saveSummary(
        schema: String,
        batchId: UUID,
        scope: String,
        periodType: String,
        content: String,
    ): UUID = transaction {
        val bs = TBatchSummaries(schema)
        bs.insert {
            it[bs.batchId] = batchId
            it[bs.scope] = scope
            it[bs.periodType] = periodType
            it[bs.content] = content
            it[bs.createdAt] = System.currentTimeMillis()
        }[bs.id]
    }

    fun deleteSummaries(schema: String, batchId: UUID) = transaction {
        val bs = TBatchSummaries(schema)
        bs.deleteWhere { bs.batchId eq batchId }
    }

    fun listSummaries(schema: String, batchId: UUID): List<BatchSummaryRow> = transaction {
        val bs = TBatchSummaries(schema)
        bs.selectAll().where { bs.batchId eq batchId }
            .orderBy(bs.createdAt)
            .map { row ->
                BatchSummaryRow(
                    id = row[bs.id],
                    batchId = row[bs.batchId],
                    scope = row[bs.scope],
                    periodType = row[bs.periodType],
                    content = row[bs.content],
                    createdAt = row[bs.createdAt],
                )
            }
    }

    private fun ResultRow.toBatchRow(b: TBatches, statusCounts: Map<String, Int>) = BatchRow(
        id = this[b.id],
        status = this[b.status],
        totalCalls = this[b.totalCalls],
        processedCalls = this[b.processedCalls],
        callTypeStats = this[b.callTypeStats],
        noSpeechCount = statusCounts["no_speech"] ?: 0,
        failedCount = statusCounts["failed"] ?: 0,
        transcribedOnlyCount = statusCounts["transcribed_only"] ?: 0,
        createdAt = this[b.createdAt],
        finishedAt = this[b.finishedAt],
    )
}
