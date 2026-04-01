package com.malikov.dto

import kotlinx.serialization.Serializable
import kotlin.math.ceil

@Serializable
data class PaginatedResponse<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
)

data class PaginationParams(
    val page: Int,
    val pageSize: Int,
) {
    val offset: Long get() = ((page - 1) * pageSize).toLong()

    companion object {
        fun from(page: Int?, pageSize: Int?): PaginationParams {
            val p = (page ?: 1).coerceAtLeast(1)
            val s = (pageSize ?: 20).coerceIn(1, 100)
            return PaginationParams(p, s)
        }
    }
}

fun <T> paginated(items: List<T>, total: Long, params: PaginationParams): PaginatedResponse<T> =
    PaginatedResponse(
        items      = items,
        total      = total,
        page       = params.page,
        pageSize   = params.pageSize,
        totalPages = ceil(total.toDouble() / params.pageSize).toInt(),
    )
