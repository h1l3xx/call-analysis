package com.malikov.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

data class UserRow(
    val id:           UUID,
    val tenantId:     UUID?,
    val email:        String,
    val passwordHash: String,
    val fullName:     String,
    val role:         String,
    val isActive:     Boolean,
    val dbSchema:     String?,
)

data class RefreshTokenRow(
    val tokenHash: String,
    val userId:    UUID,
    val expiresAt: Long,
    val revokedAt: Long?,
)

class UserRepository {

    fun findByEmail(email: String): UserRow? = transaction {
        val userRow = Users
            .selectAll()
            .where { Users.email eq email.lowercase() }
            .singleOrNull() ?: return@transaction null

        val dbSchema = userRow[Users.tenantId]?.let { tenantId ->
            Tenants
                .selectAll()
                .where { Tenants.id eq tenantId }
                .singleOrNull()
                ?.get(Tenants.dbSchema)
        }

        userRow.toUserRow(dbSchema)
    }

    fun findById(id: UUID): UserRow? = transaction {
        val userRow = Users
            .selectAll()
            .where { Users.id eq id }
            .singleOrNull() ?: return@transaction null

        val dbSchema = userRow[Users.tenantId]?.let { tenantId ->
            Tenants
                .selectAll()
                .where { Tenants.id eq tenantId }
                .singleOrNull()
                ?.get(Tenants.dbSchema)
        }

        userRow.toUserRow(dbSchema)
    }

    fun saveRefreshToken(
        tokenHash:  String,
        userId:     UUID,
        expiresAt:  Long,
        deviceInfo: String?,
        ipAddress:  String?,
    ) = transaction {
        RefreshTokens.insert {
            it[RefreshTokens.tokenHash]  = tokenHash
            it[RefreshTokens.userId]     = userId
            it[RefreshTokens.expiresAt]  = expiresAt
            it[RefreshTokens.deviceInfo] = deviceInfo
            it[RefreshTokens.ipAddress]  = ipAddress
            it[RefreshTokens.createdAt]  = System.currentTimeMillis()
        }
    }

    fun findRefreshToken(tokenHash: String): RefreshTokenRow? = transaction {
        RefreshTokens
            .selectAll()
            .where { RefreshTokens.tokenHash eq tokenHash }
            .singleOrNull()
            ?.let { row ->
                RefreshTokenRow(
                    tokenHash = row[RefreshTokens.tokenHash],
                    userId    = row[RefreshTokens.userId],
                    expiresAt = row[RefreshTokens.expiresAt],
                    revokedAt = row[RefreshTokens.revokedAt],
                )
            }
    }

    fun revokeRefreshToken(tokenHash: String) = transaction {
        RefreshTokens.update({ RefreshTokens.tokenHash eq tokenHash }) {
            it[revokedAt] = System.currentTimeMillis()
        }
    }

    fun revokeAllUserTokens(userId: UUID) = transaction {
        RefreshTokens.update({
            (RefreshTokens.userId eq userId) and (RefreshTokens.revokedAt.isNull())
        }) {
            it[revokedAt] = System.currentTimeMillis()
        }
    }

    private fun ResultRow.toUserRow(dbSchema: String?) = UserRow(
        id           = this[Users.id],
        tenantId     = this[Users.tenantId],
        email        = this[Users.email],
        passwordHash = this[Users.passwordHash],
        fullName     = this[Users.fullName],
        role         = this[Users.role],
        isActive     = this[Users.isActive],
        dbSchema     = dbSchema,
    )
}