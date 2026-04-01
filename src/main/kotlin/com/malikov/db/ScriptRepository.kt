package com.malikov.db

import com.malikov.dto.CreateCriterionRequest
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class ScriptRow(
    val id: UUID,
    val name: String,
    val callType: String,
    val description: String?,
    val isActive: Boolean,
    val criteriaCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class CriterionRow(
    val id: Int,
    val orderNum: Int,
    val name: String,
    val description: String,
    val groupType: String,
    val weight: Double,
    val scoringType: String,
    val isActive: Boolean,
)

data class ScriptDetailRow(
    val script: ScriptRow,
    val criteria: List<CriterionRow>,
)

class ScriptRepository {

    fun list(
        schema: String,
        off: Long,
        limit: Int,
        isActive: Boolean? = null,
    ): Pair<List<ScriptRow>, Long> = transaction {
        val s = TScripts(schema)
        val c = TCriteria(schema)

        val query = isActive?.let { active ->
            s.selectAll().where { s.isActive eq active }
        } ?: s.selectAll()

        val total = query.count()

        val scripts = query
            .orderBy(s.createdAt, SortOrder.DESC)
            .limit(limit, off)
            .map { row ->
                val scriptId = row[s.id]
                val count = c.selectAll().where { c.scriptId eq scriptId }.count().toInt()
                ScriptRow(
                    id             = scriptId,
                    name           = row[s.name],
                    callType       = row[s.callType],
                    description    = row[s.description],
                    isActive       = row[s.isActive],
                    criteriaCount  = count,
                    createdAt      = row[s.createdAt],
                    updatedAt      = row[s.updatedAt],
                )
            }

        scripts to total
    }

    fun findById(schema: String, scriptId: UUID): ScriptDetailRow? = transaction {
        val s = TScripts(schema)
        val c = TCriteria(schema)

        val row = s.selectAll().where { s.id eq scriptId }.singleOrNull() ?: return@transaction null

        val criteria = c.selectAll()
            .where { c.scriptId eq scriptId }
            .orderBy(c.orderNum)
            .map { cr ->
                CriterionRow(
                    id          = cr[c.id],
                    orderNum    = cr[c.orderNum],
                    name        = cr[c.name],
                    description = cr[c.description],
                    groupType   = cr[c.groupType],
                    weight      = cr[c.weight],
                    scoringType = cr[c.scoringType],
                    isActive    = cr[c.isActive],
                )
            }

        ScriptDetailRow(
            script = ScriptRow(
                id            = row[s.id],
                name          = row[s.name],
                callType      = row[s.callType],
                description   = row[s.description],
                isActive      = row[s.isActive],
                criteriaCount = criteria.size,
                createdAt     = row[s.createdAt],
                updatedAt     = row[s.updatedAt],
            ),
            criteria = criteria,
        )
    }

    fun findDefault(schema: String): ScriptDetailRow? = transaction {
        val s = TScripts(schema)
        val c = TCriteria(schema)

        val row = s.selectAll()
            .where { (s.isActive eq true) and (s.isDefault eq true) }
            .firstOrNull()
            ?: s.selectAll().where { s.isActive eq true }.firstOrNull()
            ?: return@transaction null

        val scriptId = row[s.id]
        val criteria = c.selectAll()
            .where { c.scriptId eq scriptId }
            .orderBy(c.orderNum)
            .map { cr ->
                CriterionRow(
                    id          = cr[c.id],
                    orderNum    = cr[c.orderNum],
                    name        = cr[c.name],
                    description = cr[c.description],
                    groupType   = cr[c.groupType],
                    weight      = cr[c.weight],
                    scoringType = cr[c.scoringType],
                    isActive    = cr[c.isActive],
                )
            }

        ScriptDetailRow(
            script = ScriptRow(
                id            = row[s.id],
                name          = row[s.name],
                callType      = row[s.callType],
                description   = row[s.description],
                isActive      = row[s.isActive],
                criteriaCount = criteria.size,
                createdAt     = row[s.createdAt],
                updatedAt     = row[s.updatedAt],
            ),
            criteria = criteria,
        )
    }

    fun create(
        schema: String,
        name: String,
        callType: String,
        description: String?,
        criteria: List<CreateCriterionRequest>,
    ): UUID = transaction {
        val s = TScripts(schema)
        val c = TCriteria(schema)
        val now = System.currentTimeMillis()

        val scriptId = s.insert {
            it[s.name]        = name
            it[s.callType]    = callType
            it[s.description] = description
            it[s.createdAt]   = now
            it[s.updatedAt]   = now
        }[s.id]

        criteria.forEach { cr ->
            c.insert {
                it[c.scriptId]    = scriptId
                it[c.orderNum]    = cr.orderNum
                it[c.name]        = cr.name
                it[c.description] = cr.description
                it[c.groupType]   = cr.groupType
                it[c.weight]      = cr.weight
                it[c.scoringType] = cr.scoringType
            }
        }

        scriptId
    }

    fun update(
        schema: String,
        scriptId: UUID,
        name: String?,
        callType: String?,
        description: String?,
        isActive: Boolean?,
        criteria: List<CreateCriterionRequest>?,
    ): Boolean = transaction {
        val s = TScripts(schema)
        val c = TCriteria(schema)
        val now = System.currentTimeMillis()

        val updated = s.update({ s.id eq scriptId }) {
            name?.let { v -> it[s.name] = v }
            callType?.let { v -> it[s.callType] = v }
            description?.let { v -> it[s.description] = v }
            isActive?.let { v -> it[s.isActive] = v }
            it[s.updatedAt] = now
        }

        if (updated == 0) return@transaction false

        if (criteria != null) {
            c.deleteWhere { c.scriptId eq scriptId }
            criteria.forEach { cr ->
                c.insert {
                    it[c.scriptId]    = scriptId
                    it[c.orderNum]    = cr.orderNum
                    it[c.name]        = cr.name
                    it[c.description] = cr.description
                    it[c.groupType]   = cr.groupType
                    it[c.weight]      = cr.weight
                    it[c.scoringType] = cr.scoringType
                }
            }
        }

        true
    }
}
