package com.malikov.db

import com.malikov.dto.PromptTemplateResponse
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.deleteWhere
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

    fun create(
        schema: String,
        id: String,
        name: String,
        description: String?,
        content: String,
        kind: String = "evaluation",
        isSystem: Boolean = false,
    ): PromptTemplateResponse = transaction {
        val t = TPromptTemplates(schema)
        val now = System.currentTimeMillis()
        t.insert {
            it[t.id] = id
            it[t.name] = name
            it[t.description] = description
            it[t.content] = content
            it[t.kind] = kind
            it[t.isSystem] = isSystem
            it[t.updatedAt] = now
        }
        t.selectAll().where { t.id eq id }.single().toResponse(t)
    }

    fun deleteById(schema: String, id: String): Boolean = transaction {
        val t = TPromptTemplates(schema)
        t.deleteWhere { t.id eq id } > 0
    }

    private fun ResultRow.toResponse(t: TPromptTemplates) = PromptTemplateResponse(
        id = this[t.id],
        name = this[t.name],
        description = this[t.description],
        content = this[t.content],
        kind = this[t.kind],
        isSystem = this[t.isSystem],
        updatedAt = this[t.updatedAt],
    )
}
