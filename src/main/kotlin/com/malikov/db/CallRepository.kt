package com.malikov.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class CallRow(
    val id: UUID,
    val managerId: UUID?,
    val managerName: String?,
    val scriptId: UUID?,
    val scriptName: String?,
    val status: String,
    val source: String,
    val batchId: UUID?,
    val callType: String?,
    val audioS3Key: String?,
    val audioFilename: String?,
    val durationSeconds: Int?,
    val failedStep: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val finishedAt: Long?,
)

data class TranscriptionRow(
    val rawText: String?,
    val cleanedText: String?,
    val language: String?,
    val languageProb: Double?,
    val classification: String?,
    val speakerTurns: String?,
)

data class SpeakerMetricsRow(
    val managerTalkRatio: Double?,
    val clientTalkRatio: Double?,
    val silenceRatio: Double?,
    val interruptionsCount: Int?,
    val avgPauseSeconds: Double?,
    val managerWpm: Double?,
    val clientWpm: Double?,
    val longestMonologueSec: Double?,
)

data class QualityScoreRow(
    val overallScore: Double?,
    val requiredScore: Double?,
    val optionalScore: Double?,
    val criteria: String?,
    val strengths: String?,
    val weaknesses: String?,
    val recommendations: String?,
)

data class ErrorEventRow(
    val id: Int,
    val criterionName: String?,
    val severity: String,
    val status: String?,
    val score: Double?,
    val comment: String?,
    val quote: String?,
)

data class CallResultRow(
    val call: CallRow,
    val transcription: TranscriptionRow?,
    val speakerMetrics: SpeakerMetricsRow?,
    val qualityScore: QualityScoreRow?,
    val errors: List<ErrorEventRow>,
)

class CallRepository {

    fun list(
        schema: String,
        off: Long,
        limit: Int,
        status: String? = null,
        managerId: UUID? = null,
    ): Pair<List<CallRow>, Long> = transaction {
        val cl = TCalls(schema)
        val m  = TManagers(schema)
        val s  = TScripts(schema)

        val base = cl
            .join(m, JoinType.LEFT, cl.managerId, m.id)
            .join(Users, JoinType.LEFT, m.userId, Users.id)
            .join(s, JoinType.LEFT, cl.scriptId, s.id)

        val conditions = listOfNotNull(
            status?.let { Op.build { cl.status eq it } },
            managerId?.let { Op.build { cl.managerId eq it } },
        )

        val query = if (conditions.isEmpty()) {
            base.selectAll()
        } else {
            base.selectAll().where { conditions.reduce { acc, op -> acc and op } }
        }

        val total = query.count()
        val items = query
            .orderBy(cl.createdAt, SortOrder.DESC)
            .limit(limit, off)
            .map { it.toCallRow(cl, m, s) }

        items to total
    }

    fun findById(schema: String, callId: UUID): CallRow? = transaction {
        val cl = TCalls(schema)
        val m  = TManagers(schema)
        val s  = TScripts(schema)

        cl.join(m, JoinType.LEFT, cl.managerId, m.id)
            .join(Users, JoinType.LEFT, m.userId, Users.id)
            .join(s, JoinType.LEFT, cl.scriptId, s.id)
            .selectAll()
            .where { cl.id eq callId }
            .singleOrNull()
            ?.toCallRow(cl, m, s)
    }

    fun create(
        schema: String,
        managerId: UUID,
        scriptId: UUID? = null,
        source: String,
        audioS3Key: String?,
        audioFilename: String?,
        batchId: UUID? = null,
        callType: String? = null,
    ): UUID = transaction {
        val cl = TCalls(schema)
        cl.insert {
            it[cl.managerId]     = managerId
            if (scriptId != null) it[cl.scriptId] = scriptId
            it[cl.callSource]    = source
            it[cl.audioS3Key]    = audioS3Key
            it[cl.audioFilename] = audioFilename
            if (batchId != null) it[cl.batchId] = batchId
            if (callType != null) it[cl.callType] = callType
            it[cl.createdAt]     = System.currentTimeMillis()
        }[cl.id]
    }

    fun updateAudioKey(schema: String, callId: UUID, audioKey: String) = transaction {
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) {
            it[cl.audioS3Key] = audioKey
        }
    }

    fun clearAudioKey(schema: String, callId: UUID) = transaction {
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) {
            it[cl.audioS3Key] = null
        }
    }

    fun findExpiredAudio(schema: String, olderThanMs: Long): List<Pair<UUID, String>> = transaction {
        val cl = TCalls(schema)
        cl.selectAll()
            .where { (cl.finishedAt lessEq olderThanMs) and (cl.audioS3Key.isNotNull()) }
            .map { row -> row[cl.id] to row[cl.audioS3Key]!! }
    }

    fun findResult(schema: String, callId: UUID): CallResultRow? = transaction {
        val cl = TCalls(schema)
        val m  = TManagers(schema)
        val s  = TScripts(schema)
        val t  = TTranscriptions(schema)
        val sm = TSpeakerMetrics(schema)
        val qs = TQualityScores(schema)
        val ee = TErrorEvents(schema)

        val callRow = cl.join(m, JoinType.LEFT, cl.managerId, m.id)
            .join(Users, JoinType.LEFT, m.userId, Users.id)
            .join(s, JoinType.LEFT, cl.scriptId, s.id)
            .selectAll()
            .where { cl.id eq callId }
            .singleOrNull()
            ?.toCallRow(cl, m, s)
            ?: return@transaction null

        val transcription = t.selectAll().where { t.callId eq callId }.singleOrNull()?.let { row ->
            TranscriptionRow(
                rawText        = row[t.rawText],
                cleanedText    = row[t.cleanedText],
                language       = row[t.language],
                languageProb   = row[t.languageProb],
                classification = row[t.classification],
                speakerTurns   = row[t.speakerTurns],
            )
        }

        val speakerMetrics = sm.selectAll().where { sm.callId eq callId }.singleOrNull()?.let { row ->
            SpeakerMetricsRow(
                managerTalkRatio    = row[sm.managerTalkRatio],
                clientTalkRatio     = row[sm.clientTalkRatio],
                silenceRatio        = row[sm.silenceRatio],
                interruptionsCount  = row[sm.interruptionsCount],
                avgPauseSeconds     = row[sm.avgPauseSeconds],
                managerWpm          = row[sm.managerWpm],
                clientWpm           = row[sm.clientWpm],
                longestMonologueSec = row[sm.longestMonologueSec],
            )
        }

        val qualityScore = qs.selectAll().where { qs.callId eq callId }.singleOrNull()?.let { row ->
            QualityScoreRow(
                overallScore    = row[qs.overallScore],
                requiredScore   = row[qs.requiredScore],
                optionalScore   = row[qs.optionalScore],
                criteria        = row[qs.criteria],
                strengths       = row[qs.strengths],
                weaknesses      = row[qs.weaknesses],
                recommendations = row[qs.recommendations],
            )
        }

        val errors = ee.selectAll().where { ee.callId eq callId }.orderBy(ee.id).map { row ->
            ErrorEventRow(
                id            = row[ee.id],
                criterionName = row[ee.criterionName],
                severity      = row[ee.severity],
                status        = row[ee.status],
                score         = row[ee.score],
                comment       = row[ee.comment],
                quote         = row[ee.quote],
            )
        }

        CallResultRow(callRow, transcription, speakerMetrics, qualityScore, errors)
    }

    private fun ResultRow.toCallRow(cl: TCalls, m: TManagers, s: TScripts) = CallRow(
        id              = this[cl.id],
        managerId       = this[cl.managerId],
        managerName     = this.getOrNull(Users.fullName),
        scriptId        = this[cl.scriptId],
        scriptName      = this.getOrNull(s.name),
        status          = this[cl.status],
        source          = this[cl.callSource],
        batchId         = this[cl.batchId],
        callType        = this[cl.callType],
        audioS3Key      = this[cl.audioS3Key],
        audioFilename   = this[cl.audioFilename],
        durationSeconds = this[cl.durationSeconds],
        failedStep      = this[cl.failedStep],
        errorMessage    = this[cl.errorMessage],
        createdAt       = this[cl.createdAt],
        finishedAt      = this[cl.finishedAt],
    )
}
