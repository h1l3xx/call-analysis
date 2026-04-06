package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.PromptTemplateRepository
import com.malikov.dto.PromptTemplateResponse

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

        private val KNOWN_IDS = setOf("internal_eval", "external_eval")

        fun defaultContent(id: String): String = when (id) {
            "internal_eval" -> DEFAULT_INTERNAL_INSTRUCTIONS
            "external_eval" -> DEFAULT_EXTERNAL_INSTRUCTIONS
            else -> ""
        }
    }

    fun list(schema: String): List<PromptTemplateResponse> =
        repo.findAll(schema).filter { it.id in KNOWN_IDS }

    fun getById(schema: String, id: String): PromptTemplateResponse =
        repo.findById(schema, id) ?: throw NotFoundException("Prompt template '$id' not found")

    fun update(schema: String, id: String, content: String): PromptTemplateResponse {
        require(id in KNOWN_IDS) { "Unknown template: $id" }
        require(content.isNotBlank()) { "Содержимое не может быть пустым" }

        val updated = repo.updateContent(schema, id, content)
        if (!updated) throw NotFoundException("Prompt template '$id' not found")
        return getById(schema, id)
    }

    fun reset(schema: String, id: String): PromptTemplateResponse {
        val default = defaultContent(id)
        require(default.isNotEmpty()) { "Unknown template id: $id" }
        repo.updateContent(schema, id, default)
        return getById(schema, id)
    }

    fun getContent(schema: String, id: String): String =
        repo.findContentById(schema, id) ?: defaultContent(id)
}
