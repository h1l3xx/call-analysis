package com.malikov.db

import com.malikov.dto.PromptTemplateResponse
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.ResultRow

class PromptTemplateRepository {

    fun findAll(schema: String): List<PromptTemplateResponse> = transaction {
        val t = TPromptTemplates(schema)
        t.selectAll().orderBy(t.id).map { it.toResponse(t) }
    }

    fun findById(schema: String, id: String): PromptTemplateResponse? = transaction {
        val t = TPromptTemplates(schema)
        t.selectAll().where { t.id eq id }.singleOrNull()?.toResponse(t)
    }

    fun findContentById(schema: String, id: String): String? = transaction {
        val t = TPromptTemplates(schema)
        t.selectAll().where { t.id eq id }.singleOrNull()?.get(t.content)
    }

    fun updateContent(schema: String, id: String, content: String): Boolean = transaction {
        val t = TPromptTemplates(schema)
        t.update({ t.id eq id }) {
            it[t.content] = content
            it[t.updatedAt] = System.currentTimeMillis()
        } > 0
    }

    private fun ResultRow.toResponse(t: TPromptTemplates) = PromptTemplateResponse(
        id = this[t.id],
        name = this[t.name],
        description = this[t.description],
        content = this[t.content],
        updatedAt = this[t.updatedAt],
    )
}
