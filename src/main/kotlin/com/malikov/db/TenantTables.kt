package com.malikov.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.postgresql.util.PGobject

class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "JSONB"
    override fun valueFromDB(value: Any): String = when (value) {
        is PGobject -> value.value ?: ""
        is String -> value
        else -> value.toString()
    }
    override fun notNullValueToDB(value: String): Any {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = value
        return obj
    }
}

fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())

// Каждый класс таблицы принимает schema и строит qualified name: "$schema.table_name".
// Экземпляры создаются на каждый запрос — это дёшево, Exposed Table — это просто описание.

class TDepartments(schema: String) : Table("$schema.departments") {
    val id          = uuid("id").autoGenerate()
    val name        = text("name")
    val description = text("description").nullable()
    val isActive    = bool("is_active").default(true)
    val createdAt   = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

class TDepartmentLeads(schema: String) : Table("$schema.department_leads") {
    val userId       = uuid("user_id")
    val departmentId = uuid("department_id")
    val createdAt    = long("created_at")

    override val primaryKey = PrimaryKey(userId, departmentId)
}

class TManagers(schema: String) : Table("$schema.managers") {
    val id           = uuid("id").autoGenerate()
    val userId       = uuid("user_id")
    val departmentId = uuid("department_id").nullable()
    val extension    = text("extension").nullable()
    val phoneNumber  = text("phone_number").nullable()
    val isActive     = bool("is_active").default(true)
    val createdAt    = long("created_at")
    val updatedAt    = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

class TScripts(schema: String) : Table("$schema.scripts") {
    val id          = uuid("id").autoGenerate()
    val name        = text("name")
    val callType    = text("call_type")
    val description = text("description").nullable()
    val isActive    = bool("is_active").default(true)
    val isDefault   = bool("is_default").default(false)
    val createdAt   = long("created_at")
    val updatedAt   = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

class TCriteria(schema: String) : Table("$schema.criteria") {
    val id          = integer("id").autoIncrement()
    val scriptId    = uuid("script_id")
    val orderNum    = integer("order_num")
    val name        = text("name")
    val description = text("description")
    val groupType   = text("group_type").default("required")
    val weight      = double("weight").default(1.0)
    val scoringType = text("scoring_type").default("binary")
    val isActive    = bool("is_active").default(true)

    override val primaryKey = PrimaryKey(id)
}

class TCalls(schema: String) : Table("$schema.calls") {
    val id              = uuid("id").autoGenerate()
    val managerId       = uuid("manager_id").nullable()
    val scriptId        = uuid("script_id").nullable()
    val batchId         = uuid("batch_id").nullable()
    val callType        = text("call_type").nullable()
    val status          = text("status").default("queued")
    val callSource      = text("source").default("direct")
    val audioS3Key      = text("audio_s3_key").nullable()
    val audioFilename   = text("audio_filename").nullable()
    val durationSeconds = integer("duration_seconds").nullable()
    val failedStep      = text("failed_step").nullable()
    val errorMessage    = text("error_message").nullable()
    val createdAt       = long("created_at")
    val finishedAt      = long("finished_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

class TTranscriptions(schema: String) : Table("$schema.transcriptions") {
    val callId         = uuid("call_id")
    val rawText        = text("raw_text").nullable()
    val cleanedText    = text("cleaned_text").nullable()
    val language       = text("language").default("ru")
    val languageProb   = double("language_prob").nullable()
    val classification = text("classification").nullable()
    val speakerTurns   = jsonb("speaker_turns").nullable()
    val createdAt      = long("created_at")

    override val primaryKey = PrimaryKey(callId)
}

class TSpeakerMetrics(schema: String) : Table("$schema.speaker_metrics") {
    val callId              = uuid("call_id")
    val managerTalkRatio    = double("manager_talk_ratio").nullable()
    val clientTalkRatio     = double("client_talk_ratio").nullable()
    val silenceRatio        = double("silence_ratio").nullable()
    val interruptionsCount  = integer("interruptions_count").nullable()
    val avgPauseSeconds     = double("avg_pause_seconds").nullable()
    val managerWpm          = double("manager_wpm").nullable()
    val clientWpm           = double("client_wpm").nullable()
    val longestMonologueSec = double("longest_monologue_sec").nullable()
    val createdAt           = long("created_at")

    override val primaryKey = PrimaryKey(callId)
}

class TQualityScores(schema: String) : Table("$schema.quality_scores") {
    val callId          = uuid("call_id")
    val scriptId        = uuid("script_id").nullable()
    val overallScore    = double("overall_score").nullable()
    val requiredScore   = double("required_score").nullable()
    val optionalScore   = double("optional_score").nullable()
    val criteria        = jsonb("criteria").nullable()
    val strengths       = jsonb("strengths").nullable()
    val weaknesses      = jsonb("weaknesses").nullable()
    val recommendations = jsonb("recommendations").nullable()
    val processedAt     = long("processed_at")

    override val primaryKey = PrimaryKey(callId)
}

class TErrorEvents(schema: String) : Table("$schema.error_events") {
    val id            = integer("id").autoIncrement()
    val callId        = uuid("call_id")
    val criterionId   = integer("criterion_id").nullable()
    val criterionName = text("criterion_name").nullable()
    val severity      = text("severity").default("medium")
    val status        = text("status").nullable()
    val score         = double("score").nullable()
    val comment       = text("comment").nullable()
    val quote         = text("quote").nullable()
    val createdAt     = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

class TUsageLog(schema: String) : Table("$schema.usage_log") {
    val id            = integer("id").autoIncrement()
    val callId        = uuid("call_id")
    val minutesBilled = double("minutes_billed")
    val billedAt      = long("billed_at")

    override val primaryKey = PrimaryKey(id)
}

class TBatches(schema: String) : Table("$schema.batches") {
    val id              = uuid("id").autoGenerate()
    val status          = text("status").default("uploading")
    val totalCalls      = integer("total_calls").default(0)
    val processedCalls  = integer("processed_calls").default(0)
    val callTypeStats   = jsonb("call_type_stats").nullable()
    val createdAt       = long("created_at")
    val finishedAt      = long("finished_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

class TBatchSummaries(schema: String) : Table("$schema.batch_summaries") {
    val id         = uuid("id").autoGenerate()
    val batchId    = uuid("batch_id")
    val scope      = text("scope").default("all")
    val periodType = text("period_type").default("batch")
    val content    = jsonb("content").nullable()
    val createdAt  = long("created_at")

    override val primaryKey = PrimaryKey(id)
}
