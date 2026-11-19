package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "query_cache_meta",
    indices = [
        Index(value = ["category"]),
        Index(value = ["fetchedAtMillis"]),
    ],
)
data class QueryCacheMetaEntity(
    @PrimaryKey val cacheKey: String,
    val fetchedAtMillis: Long,
    val category: String = "",
    val expiresAtMillis: Long = 0L,
)
