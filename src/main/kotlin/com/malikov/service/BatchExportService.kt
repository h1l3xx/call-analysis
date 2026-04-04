package com.malikov.service

import com.malikov.db.*
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

class BatchExportService(
    private val batchRepo: BatchRepository,
    private val callRepo: CallRepository,
    private val managerRepo: ManagerRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm").apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }

    fun generateCsv(schema: String, batchId: UUID): String {
        val batch = batchRepo.findById(schema, batchId)
            ?: throw IllegalArgumentException("Batch not found")
        val summaries = batchRepo.listSummaries(schema, batchId)
        val results = callRepo.findResultsByBatch(schema, batchId)

        val allMgrIds = (results.mapNotNull { it.call.managerId } +
                results.mapNotNull { it.call.secondManagerId }).distinct()
        val secondNames = if (allMgrIds.isNotEmpty())
            callRepo.resolveManagerNames(schema, allMgrIds) else emptyMap()
        val sharedMap = if (allMgrIds.isNotEmpty())
            managerRepo.findSharedExtensionNames(schema, allMgrIds) else emptyMap()

        val sb = StringBuilder()
        sb.append('\uFEFF')

        appendSummarySection(sb, batch, summaries)
        sb.appendLine()
        appendCallsSection(sb, results, secondNames, sharedMap)

        return sb.toString()
    }

    private fun appendSummarySection(
        sb: StringBuilder,
        batch: BatchRow,
        summaries: List<BatchSummaryRow>,
    ) {
        sb.appendLine(csvRow("Батч", batch.id.toString()))
        sb.appendLine(csvRow("Статус", batch.status))
        sb.appendLine(csvRow("Всего звонков", batch.totalCalls.toString()))
        sb.appendLine(csvRow("Обработано", batch.processedCalls.toString()))
        sb.appendLine(csvRow("Создан", dateFormat.format(Date(batch.createdAt))))
        batch.finishedAt?.let {
            sb.appendLine(csvRow("Завершён", dateFormat.format(Date(it))))
        }

        for (summary in summaries) {
            sb.appendLine()
            val scopeLabel = when (summary.scope) {
                "internal" -> "Внутренние"
                "external" -> "Внешние"
                else -> "Все"
            }
            sb.appendLine(csvRow("Отчёт ($scopeLabel)"))

            val content = summary.content?.let { tryParseJson(it) }
            if (content != null) {
                content["summary_text"]?.jsonPrimitive?.contentOrNull?.let {
                    sb.appendLine(csvRow("Резюме", it))
                }
                content["total_calls"]?.jsonPrimitive?.contentOrNull?.let {
                    sb.appendLine(csvRow("Звонков в отчёте", it))
                }
                content["avg_score"]?.jsonPrimitive?.contentOrNull?.let {
                    sb.appendLine(csvRow("Средний балл", it))
                }
                appendJsonArray(sb, content, "top_recommendations", "Рекомендации")
                appendJsonArray(sb, content, "recurring_patterns", "Повторяющиеся паттерны")
                appendJsonArray(sb, content, "common_client_complaints", "Частые жалобы клиентов")
            } else if (summary.content != null) {
                sb.appendLine(csvRow("Содержание", summary.content))
            }
        }
    }

    private fun appendJsonArray(
        sb: StringBuilder,
        obj: JsonObject,
        key: String,
        label: String,
    ) {
        val arr = obj[key]?.jsonArray ?: return
        if (arr.isEmpty()) return
        val items = arr.mapNotNull { it.jsonPrimitive.contentOrNull }
        if (items.isNotEmpty()) {
            sb.appendLine(csvRow(label, items.joinToString("; ")))
        }
    }

    private fun appendCallsSection(
        sb: StringBuilder,
        results: List<CallResultRow>,
        secondNames: Map<UUID, String>,
        sharedMap: Map<UUID, List<String>>,
    ) {
        sb.appendLine(csvRow(
            "Сотрудник 1", "Сотрудник 2", "Тип звонка", "Статус",
            "Длительность (сек)", "Файл", "Дата",
            "Оценка", "Описание",
            "Сильные стороны", "Слабые стороны", "Рекомендации",
            "Доля спикера 1 %", "Доля спикера 2 %", "Тишина %",
            "Перебивания", "Ср. пауза (сек)", "WPM спикер 1", "WPM спикер 2",
            "Транскрипция",
        ))

        for (r in results) {
            val c = r.call
            val q = r.qualityScore
            val m = r.speakerMetrics

            val name1 = c.managerId?.let { sharedMap[it] }
                ?.joinToString(" / ")
                ?: c.managerName ?: ""
            val name2 = c.secondManagerId?.let { sharedMap[it] }
                ?.joinToString(" / ")
                ?: c.secondManagerId?.let { secondNames[it] } ?: ""

            sb.appendLine(csvRow(
                name1,
                name2,
                callTypeLabel(c.callType),
                statusLabel(c.status),
                c.durationSeconds?.toString() ?: "",
                c.audioFilename ?: "",
                dateFormat.format(Date(c.createdAt)),
                q?.overallScore?.let { "%.1f".format(it) } ?: "",
                q?.summary ?: "",
                flattenJsonList(q?.strengths),
                flattenJsonList(q?.weaknesses),
                flattenJsonList(q?.recommendations),
                m?.managerTalkRatio?.let { "%.1f".format(it * 100) } ?: "",
                m?.clientTalkRatio?.let { "%.1f".format(it * 100) } ?: "",
                m?.silenceRatio?.let { "%.1f".format(it * 100) } ?: "",
                m?.interruptionsCount?.toString() ?: "",
                m?.avgPauseSeconds?.let { "%.1f".format(it) } ?: "",
                m?.managerWpm?.let { "%.0f".format(it) } ?: "",
                m?.clientWpm?.let { "%.0f".format(it) } ?: "",
                r.transcription?.cleanedText ?: r.transcription?.rawText ?: "",
            ))
        }
    }

    private fun flattenJsonList(jsonStr: String?): String {
        if (jsonStr.isNullOrBlank()) return ""
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString("; ")
        } catch (_: Exception) {
            jsonStr
        }
    }

    private fun tryParseJson(s: String): JsonObject? = try {
        json.parseToJsonElement(s).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun csvRow(vararg values: String): String =
        values.joinToString(";") { escapeCsv(it) }

    private fun escapeCsv(value: String): String {
        val needsQuoting = value.contains(';') || value.contains('"') ||
                value.contains('\n') || value.contains('\r')
        return if (needsQuoting) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private fun callTypeLabel(ct: String?): String = when (ct) {
        "internal" -> "Внутренний"
        "external" -> "Внешний"
        else -> "Неизвестный"
    }

    private fun statusLabel(s: String): String = when (s) {
        "queued" -> "В очереди"
        "processing" -> "Обработка"
        "transcribed" -> "Транскрибирован"
        "evaluated" -> "Оценён"
        "done" -> "Завершён"
        "failed" -> "Ошибка"
        else -> s
    }
}
