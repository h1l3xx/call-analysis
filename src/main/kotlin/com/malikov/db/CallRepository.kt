package com.malikov.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class CallRow(
    val id: UUID,
    val managerId: UUID?,
    val managerName: String?,
    val secondManagerId: UUID?,
    val secondManagerName: String?,
    val scriptId: UUID?,
    val scriptName: String?,
    val status: String,
    val source: String,
    val batchId: UUID?,
    val callType: String?,
    val callDirection: String? = null,
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
    val summary: String?,
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
        managerIds: List<UUID>? = null,
        departmentId: UUID? = null,
        search: String? = null,
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
            managerIds?.takeIf { it.isNotEmpty() }?.let { ids ->
                Op.build { cl.managerId inList ids }
            },
            departmentId?.let { Op.build { m.departmentId eq it } },
            search?.takeIf { it.isNotBlank() }?.let { q ->
                val pattern = "%${q.lowercase()}%"
                Op.build {
                    (Users.fullName.lowerCase() like pattern) or
                    (cl.audioFilename.lowerCase() like pattern)
                }
            },
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
        callDirection: String? = null,
        secondManagerId: UUID? = null,
    ): UUID = transaction {
        val cl = TCalls(schema)
        cl.insert {
            it[cl.managerId]     = managerId
            if (secondManagerId != null) it[cl.secondManagerId] = secondManagerId
            if (scriptId != null) it[cl.scriptId] = scriptId
            it[cl.callSource]    = source
            it[cl.audioS3Key]    = audioS3Key
            it[cl.audioFilename] = audioFilename
            if (batchId != null) it[cl.batchId] = batchId
            if (callType != null) it[cl.callType] = callType
            if (callDirection != null) it[cl.callDirection] = callDirection
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

    fun getAudioKey(schema: String, callId: UUID): String? = transaction {
        val cl = TCalls(schema)
        cl.selectAll().where { cl.id eq callId }
            .singleOrNull()?.get(cl.audioS3Key)
    }

    fun listAudioKeysByBatch(schema: String, batchId: UUID): List<Pair<UUID, String>> = transaction {
        val cl = TCalls(schema)
        cl.selectAll()
            .where { (cl.batchId eq batchId) and cl.audioS3Key.isNotNull() }
            .map { it[cl.id] to it[cl.audioS3Key]!! }
    }

    fun markNoSpeech(schema: String, callId: UUID, reason: String) = transaction {
        val cl = TCalls(schema)
        cl.update({ cl.id eq callId }) {
            it[cl.status]       = "no_speech"
            it[cl.errorMessage] = reason.take(2000)
            it[cl.finishedAt]   = System.currentTimeMillis()
        }
    }

    fun deleteById(schema: String, callId: UUID): Int = transaction {
        val cl = TCalls(schema)
        cl.deleteWhere { cl.id eq callId }
    }

    fun deleteByBatch(schema: String, batchId: UUID): Int = transaction {
        val cl = TCalls(schema)
        cl.deleteWhere { cl.batchId eq batchId }
    }

    fun findResultsByBatch(
        schema: String,
        batchId: UUID,
        departmentId: UUID? = null,
        managerIds: List<UUID>? = null,
    ): List<CallResultRow> = transaction {
        val cl = TCalls(schema)
        val m  = TManagers(schema)
        val s  = TScripts(schema)
        val t  = TTranscriptions(schema)
        val sm = TSpeakerMetrics(schema)
        val qs = TQualityScores(schema)

        val conditions = listOfNotNull(
            Op.build { cl.batchId eq batchId },
            departmentId?.let { Op.build { m.departmentId eq it } },
            managerIds?.takeIf { it.isNotEmpty() }?.let { ids ->
                Op.build { cl.managerId inList ids }
            },
        )

        val callRows = cl.join(m, JoinType.LEFT, cl.managerId, m.id)
            .join(Users, JoinType.LEFT, m.userId, Users.id)
            .join(s, JoinType.LEFT, cl.scriptId, s.id)
            .selectAll()
            .where { conditions.reduce { acc, op -> acc and op } }
            .orderBy(cl.createdAt, SortOrder.ASC)
            .map { it.toCallRow(cl, m, s) }

        val callIds = callRows.map { it.id }
        if (callIds.isEmpty()) return@transaction emptyList()

        val transcriptions = t.selectAll()
            .where { t.callId inList callIds }
            .associate { row ->
                row[t.callId] to TranscriptionRow(
                    rawText        = row[t.rawText],
                    cleanedText    = row[t.cleanedText],
                    language       = row[t.language],
                    languageProb   = row[t.languageProb],
                    classification = row[t.classification],
                    speakerTurns   = row[t.speakerTurns],
                )
            }

        val metrics = sm.selectAll()
            .where { sm.callId inList callIds }
            .associate { row ->
                row[sm.callId] to SpeakerMetricsRow(
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

        val quality = qs.selectAll()
            .where { qs.callId inList callIds }
            .associate { row ->
                row[qs.callId] to QualityScoreRow(
                    overallScore    = row[qs.overallScore],
                    requiredScore   = row[qs.requiredScore],
                    optionalScore   = row[qs.optionalScore],
                    criteria        = row[qs.criteria],
                    strengths       = row[qs.strengths],
                    weaknesses      = row[qs.weaknesses],
                    recommendations = row[qs.recommendations],
                    summary         = row[qs.summary],
                )
            }

        callRows.map { call ->
            CallResultRow(
                call           = call,
                transcription  = transcriptions[call.id],
                speakerMetrics = metrics[call.id],
                qualityScore   = quality[call.id],
                errors         = emptyList(),
            )
        }
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
                summary         = row[qs.summary],
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

    fun findResultsByFilters(
        schema: String,
        departmentId: UUID? = null,
        managerIds: List<UUID>? = null,
        status: String? = null,
        callType: String? = null,
        sinceMs: Long? = null,
        untilMs: Long? = null,
        search: String? = null,
    ): List<CallResultRow> = transaction {
        val cl = TCalls(schema)
        val m  = TManagers(schema)
        val s  = TScripts(schema)
        val t  = TTranscriptions(schema)
        val sm = TSpeakerMetrics(schema)
        val qs = TQualityScores(schema)

        val conditions = listOfNotNull(
            departmentId?.let { Op.build { m.departmentId eq it } },
            managerIds?.takeIf { it.isNotEmpty() }?.let { ids ->
                Op.build { cl.managerId inList ids }
            },
            status?.let { Op.build { cl.status eq it } },
            callType?.let { Op.build { cl.callType eq it } },
            sinceMs?.let { Op.build { cl.createdAt greaterEq it } },
            untilMs?.let { Op.build { cl.createdAt lessEq it } },
            search?.takeIf { it.isNotBlank() }?.let { q ->
                val pattern = "%${q.lowercase()}%"
                Op.build {
                    (Users.fullName.lowerCase() like pattern) or
                    (cl.audioFilename.lowerCase() like pattern)
                }
            },
        )

        val base = cl.join(m, JoinType.LEFT, cl.managerId, m.id)
            .join(Users, JoinType.LEFT, m.userId, Users.id)
            .join(s, JoinType.LEFT, cl.scriptId, s.id)

        val callRows = if (conditions.isEmpty()) {
            base.selectAll()
        } else {
            base.selectAll().where { conditions.reduce { acc, op -> acc and op } }
        }.orderBy(cl.createdAt, SortOrder.ASC).map { it.toCallRow(cl, m, s) }

        val callIds = callRows.map { it.id }
        if (callIds.isEmpty()) return@transaction emptyList()

        val transcriptions = t.selectAll()
            .where { t.callId inList callIds }
            .associate { row ->
                row[t.callId] to TranscriptionRow(
                    rawText        = row[t.rawText],
                    cleanedText    = row[t.cleanedText],
                    language       = row[t.language],
                    languageProb   = row[t.languageProb],
                    classification = row[t.classification],
                    speakerTurns   = row[t.speakerTurns],
                )
            }

        val metrics = sm.selectAll()
            .where { sm.callId inList callIds }
            .associate { row ->
                row[sm.callId] to SpeakerMetricsRow(
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

        val quality = qs.selectAll()
            .where { qs.callId inList callIds }
            .associate { row ->
                row[qs.callId] to QualityScoreRow(
                    overallScore    = row[qs.overallScore],
                    requiredScore   = row[qs.requiredScore],
                    optionalScore   = row[qs.optionalScore],
                    criteria        = row[qs.criteria],
                    strengths       = row[qs.strengths],
                    weaknesses      = row[qs.weaknesses],
                    recommendations = row[qs.recommendations],
                    summary         = row[qs.summary],
                )
            }

        callRows.map { call ->
            CallResultRow(
                call           = call,
                transcription  = transcriptions[call.id],
                speakerMetrics = metrics[call.id],
                qualityScore   = quality[call.id],
                errors         = emptyList(),
            )
        }
    }

    fun resolveManagerNames(schema: String, managerIds: List<UUID>): Map<UUID, String> = transaction {
        if (managerIds.isEmpty()) return@transaction emptyMap()
        val m = TManagers(schema)
        m.join(Users, JoinType.INNER, m.userId, Users.id)
            .selectAll()
            .where { m.id inList managerIds }
            .associate { it[m.id] to it[Users.fullName] }
    }

    fun countByStatus(
        schema: String,
        managerId: UUID? = null,
        since: Long? = null,
        until: Long? = null,
    ): Map<String, Long> = transaction {
        val cl = TCalls(schema)
        val conditions = mutableListOf<Op<Boolean>>()
        if (managerId != null) conditions.add(Op.build { cl.managerId eq managerId })
        if (since != null)     conditions.add(Op.build { cl.createdAt greaterEq since })
        if (until != null)     conditions.add(Op.build { cl.createdAt lessEq until })
        val where = conditions.reduceOrNull { a, b -> a and b }
        val query = if (where != null)
            cl.select(cl.status, cl.id.count()).where(where)
        else
            cl.select(cl.status, cl.id.count())
        query.groupBy(cl.status)
            .associate { it[cl.status] to it[cl.id.count()] }
    }

    fun avgScore(
        schema: String,
        managerId: UUID? = null,
        since: Long? = null,
        until: Long? = null,
    ): Double? = transaction {
        val cl = TCalls(schema)
        val qs = TQualityScores(schema)
        val conditions = mutableListOf<Op<Boolean>>(
            Op.build { cl.status eq "done" },
            Op.build { qs.overallScore.isNotNull() },
        )
        if (managerId != null) conditions.add(Op.build { cl.managerId eq managerId })
        if (since != null)     conditions.add(Op.build { cl.createdAt greaterEq since })
        if (until != null)     conditions.add(Op.build { cl.createdAt lessEq until })
        val where = conditions.reduce { a, b -> a and b }
        cl.join(qs, JoinType.INNER, cl.id, qs.callId)
            .select(qs.overallScore.avg())
            .where(where)
            .singleOrNull()
            ?.get(qs.overallScore.avg())
            ?.toDouble()
    }

    private fun ResultRow.toCallRow(cl: TCalls, m: TManagers, s: TScripts) = CallRow(
        id                = this[cl.id],
        managerId         = this[cl.managerId],
        managerName       = this.getOrNull(Users.fullName),
        secondManagerId   = this[cl.secondManagerId],
        secondManagerName = null,
        scriptId          = this[cl.scriptId],
        scriptName        = this.getOrNull(s.name),
        status            = this[cl.status],
        source            = this[cl.callSource],
        batchId           = this[cl.batchId],
        callType          = this[cl.callType],
        callDirection     = this[cl.callDirection],
        audioS3Key        = this[cl.audioS3Key],
        audioFilename     = this[cl.audioFilename],
        durationSeconds   = this[cl.durationSeconds],
        failedStep        = this[cl.failedStep],
        errorMessage      = this[cl.errorMessage],
        createdAt         = this[cl.createdAt],
        finishedAt        = this[cl.finishedAt],
    )
}
