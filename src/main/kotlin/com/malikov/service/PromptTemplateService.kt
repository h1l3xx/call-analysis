package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.PromptTemplateRepository
import com.malikov.dto.CreatePromptTemplateRequest
import com.malikov.dto.PromptTemplateResponse
import java.util.UUID

class PromptTemplateService(
    private val repo: PromptTemplateRepository,
) {
    companion object {
        val DEFAULT_INTERNAL_INSTRUCTIONS = """
Оцени разговор по 5 критериям эффективного внутреннего общения:

1. Ясность и структурированность (0–100): чёткость формулировок, логика изложения, отсутствие двусмысленности.
2. Результативность (0–100): наличие конкретных решений, action items, ответственных и дедлайнов.
3. Профессионализм (0–100): деловой тон, отсутствие конфликтности и эмоциональных срывов, уважительное общение.
4. Эффективность времени (0–100): отношение полезного содержания к общей длительности, отсутствие уходов от темы.
5. Соблюдение процедур (0–100): следование регламентам, корректная эскалация при необходимости, фиксация договорённостей.

Также напиши краткое описание звонка (2–3 предложения): кто звонил, по какому поводу, чем закончился.""".trimIndent()

        val DEFAULT_EXTERNAL_INSTRUCTIONS = "Оцени каждый критерий. Также напиши краткое описание звонка (2–3 предложения): кто обратился, с какой целью, чем закончился разговор."

        private val KNOWN_IDS = setOf(
            "internal_eval",
            "external_eval",
            "eval_internal",
            "eval_internal_incoming",
            "eval_internal_outgoing",
            "eval_external_incoming",
            "eval_external_outgoing",
        )
        private val HIDDEN_TECHNICAL_IDS = setOf(
            "internal_eval",
            "external_eval",
            "eval_internal",
        )
        private val CORE_DIRECTION_IDS = listOf(
            "eval_internal_incoming",
            "eval_internal_outgoing",
            "eval_external_incoming",
            "eval_external_outgoing",
        )
        private val ALLOWED_DIRECTIONS = setOf(
            "internal_incoming",
            "internal_outgoing",
            "external_incoming",
            "external_outgoing",
        )

        fun defaultContent(id: String): String = when (id) {
            "internal_eval", "eval_internal", "eval_internal_incoming", "eval_internal_outgoing" -> DEFAULT_INTERNAL_INSTRUCTIONS
            "external_eval", "eval_external_incoming", "eval_external_outgoing" -> DEFAULT_EXTERNAL_INSTRUCTIONS
            else -> ""
        }
    }

    fun list(schema: String): List<PromptTemplateResponse> {
        val all = repo.findAll(schema).filter { isEvaluationTemplate(it) && it.id !in HIDDEN_TECHNICAL_IDS }
        val byId = all.associateBy { it.id }
        val orderedCore = CORE_DIRECTION_IDS.mapNotNull { byId[it] }
        val rest = all
            .filterNot { it.id in CORE_DIRECTION_IDS }
            .sortedBy { it.name.lowercase() }
        return orderedCore + rest
    }

    fun getById(schema: String, id: String): PromptTemplateResponse {
        val tpl = repo.findById(schema, id) ?: throw NotFoundException("Prompt template '$id' not found")
        if (!isEvaluationTemplate(tpl)) throw NotFoundException("Prompt template '$id' not found")
        return tpl
    }

    fun update(schema: String, id: String, content: String): PromptTemplateResponse {
        val existing = getById(schema, id)
        require(isEvaluationTemplate(existing)) { "Unknown template: $id" }
        require(content.isNotBlank()) { "Содержимое не может быть пустым" }

        val updated = repo.updateContent(schema, id, content)
        if (!updated) throw NotFoundException("Prompt template '$id' not found")
        return getById(schema, id)
    }

    fun reset(schema: String, id: String): PromptTemplateResponse {
        require(id in CORE_DIRECTION_IDS || id in KNOWN_IDS) {
            "Reset доступен только для системных шаблонов"
        }
        val default = defaultContent(id)
        require(default.isNotEmpty()) { "Unknown template id: $id" }
        repo.updateContent(schema, id, default)
        return getById(schema, id)
    }

    fun create(schema: String, request: CreatePromptTemplateRequest): PromptTemplateResponse {
        val name = request.name.trim()
        require(name.isNotBlank()) { "Название не может быть пустым" }

        val direction = request.direction?.trim()?.lowercase()
        if (direction != null) {
            require(direction in ALLOWED_DIRECTIONS) { "Unsupported direction '$direction'" }
        }

        val fallbackId = when (direction) {
            "internal_incoming" -> "eval_internal_incoming"
            "internal_outgoing" -> "eval_internal_outgoing"
            "external_incoming" -> "eval_external_incoming"
            "external_outgoing" -> "eval_external_outgoing"
            else -> "eval_external_incoming"
        }
        val content = request.content?.trim()?.takeIf { it.isNotBlank() } ?: defaultContent(fallbackId)
        val id = "eval_custom_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        return repo.create(
            schema = schema,
            id = id,
            name = name,
            description = request.description?.trim()?.takeIf { it.isNotBlank() },
            content = content,
            kind = "evaluation",
            isSystem = false,
        )
    }

    fun delete(schema: String, id: String): Boolean {
        val existing = getById(schema, id)
        require(!existing.isSystem) { "Системный шаблон нельзя удалить" }
        return repo.deleteById(schema, id)
    }

    fun getContent(schema: String, id: String): String =
        repo.findContentById(schema, id) ?: defaultContent(id)

    private fun isEvaluationTemplate(tpl: PromptTemplateResponse): Boolean {
        if (tpl.kind == "evaluation") return true
        if (tpl.id.startsWith("eval_")) return true
        return tpl.id in KNOWN_IDS
    }
}
