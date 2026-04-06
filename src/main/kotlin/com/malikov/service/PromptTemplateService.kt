package com.malikov.service

import com.malikov.config.NotFoundException
import com.malikov.db.PromptTemplateRepository
import com.malikov.dto.PromptTemplateResponse

class PromptTemplateService(
    private val repo: PromptTemplateRepository,
) {
    companion object {
        private val REQUIRED_PLACEHOLDERS = mapOf(
            "system" to emptySet<String>(),
            "internal_eval" to setOf("{transcription}"),
            "external_eval" to setOf("{transcription}", "{criteria}", "{scriptName}"),
        )

        val DEFAULT_SYSTEM = "Ты — эксперт по оценке качества телефонных разговоров. Отвечай ТОЛЬКО в формате JSON."

        val DEFAULT_INTERNAL_EVAL = """
Проанализируй следующий ВНУТРЕННИЙ телефонный разговор между сотрудниками компании.

Транскрипция:
---
{transcription}
---

Оцени разговор по 5 критериям эффективного внутреннего общения:

1. **Ясность и структурированность** (0–100): чёткость формулировок, логика изложения, отсутствие двусмысленности.
2. **Результативность** (0–100): наличие конкретных решений, action items, ответственных и дедлайнов.
3. **Профессионализм** (0–100): деловой тон, отсутствие конфликтности и эмоциональных срывов, уважительное общение.
4. **Эффективность времени** (0–100): отношение полезного содержания к общей длительности, отсутствие уходов от темы.
5. **Соблюдение процедур** (0–100): следование регламентам, корректная эскалация при необходимости, фиксация договорённостей.

Также напиши краткое описание звонка (2–3 предложения): кто звонил, по какому поводу, чем закончился.

Ответ СТРОГО в JSON формате:
{
  "summary": "<краткое описание: кто звонил, тема, итог — 2-3 предложения>",
  "overall_score": <число от 0 до 100>,
  "criteria_scores": {
    "clarity": {"score": <0–100>, "comment": "<пояснение>"},
    "effectiveness": {"score": <0–100>, "comment": "<пояснение>"},
    "professionalism": {"score": <0–100>, "comment": "<пояснение>"},
    "time_efficiency": {"score": <0–100>, "comment": "<пояснение>"},
    "procedures": {"score": <0–100>, "comment": "<пояснение>"}
  },
  "action_items": ["конкретный action item 1", ...],
  "strengths": ["сильная сторона 1", ...],
  "weaknesses": ["слабая сторона 1", ...],
  "recommendations": ["рекомендация 1", ...]
}""".trimIndent()

        val DEFAULT_EXTERNAL_EVAL = """
Проанализируй следующий разговор менеджера с клиентом по скрипту "{scriptName}".

Транскрипция:
---
{transcription}
---

Критерии оценки:
{criteria}

Оцени каждый критерий. Также напиши краткое описание звонка (2–3 предложения): кто обратился, с какой целью, чем закончился разговор.

Ответ СТРОГО в JSON формате:
{
  "summary": "<краткое описание: кто обратился, цель, итог — 2-3 предложения>",
  "overall_score": <число от 0 до 100>,
  "criteria_evaluations": [
    {
      "id": <номер критерия>,
      "name": "<название>",
      "score": <0.0, 0.5 или 1.0>,
      "comment": "<комментарий>",
      "relevant": <true/false>
    }
  ],
  "strengths": ["сильная сторона 1", ...],
  "weaknesses": ["слабая сторона 1", ...],
  "recommendations": ["рекомендация 1", ...]
}""".trimIndent()

        fun defaultContent(id: String): String = when (id) {
            "system" -> DEFAULT_SYSTEM
            "internal_eval" -> DEFAULT_INTERNAL_EVAL
            "external_eval" -> DEFAULT_EXTERNAL_EVAL
            else -> ""
        }
    }

    fun list(schema: String): List<PromptTemplateResponse> = repo.findAll(schema)

    fun getById(schema: String, id: String): PromptTemplateResponse =
        repo.findById(schema, id) ?: throw NotFoundException("Prompt template '$id' not found")

    fun update(schema: String, id: String, content: String): PromptTemplateResponse {
        val required = REQUIRED_PLACEHOLDERS[id]
            ?: throw NotFoundException("Prompt template '$id' not found")
        val missing = required.filter { it !in content }
        require(missing.isEmpty()) {
            "Шаблон должен содержать плейсхолдеры: ${missing.joinToString(", ")}"
        }
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
