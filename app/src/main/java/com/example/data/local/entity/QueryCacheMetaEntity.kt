package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "query_cache_meta")
data class QueryCacheMetaEntity(
    @PrimaryKey val cacheKey: String,
    val fetchedAtMillis: Long,
)
