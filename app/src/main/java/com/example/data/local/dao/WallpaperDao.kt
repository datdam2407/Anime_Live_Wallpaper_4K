package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.CachedWallpaperEntity
import com.example.data.local.entity.FavoriteWallpaperEntity
import com.example.data.local.entity.QueryCacheMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WallpaperDao {
    @Query("SELECT * FROM favorite_wallpapers ORDER BY savedAt DESC")
    fun observeFavorites(): Flow<List<FavoriteWallpaperEntity>>

    @Query("SELECT id FROM favorite_wallpapers")
    fun observeFavoriteIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_wallpapers WHERE id = :id)")
    suspend fun isFavorite(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(entity: FavoriteWallpaperEntity)

    @Query("DELETE FROM favorite_wallpapers WHERE id = :id")
    suspend fun deleteFavoriteById(id: String)

    @Query("SELECT * FROM cached_wallpapers WHERE cacheKey = :cacheKey ORDER BY positionIndex ASC")
    fun observeCachedWallpapers(cacheKey: String): Flow<List<CachedWallpaperEntity>>

    @Query("SELECT * FROM cached_wallpapers WHERE category = :category ORDER BY cachedAt DESC, positionIndex ASC")
    fun observeCachedWallpapersByCategory(category: String): Flow<List<CachedWallpaperEntity>>

    @Query("SELECT * FROM cached_wallpapers WHERE cacheKey = :cacheKey ORDER BY positionIndex ASC")
    suspend fun getCachedWallpapers(cacheKey: String): List<CachedWallpaperEntity>

    @Query("SELECT * FROM cached_wallpapers WHERE category = :category ORDER BY cachedAt DESC, positionIndex ASC")
    suspend fun getCachedWallpapersByCategorySync(category: String): List<CachedWallpaperEntity>

    @Query("SELECT * FROM query_cache_meta WHERE cacheKey = :cacheKey")
    suspend fun getCacheMeta(cacheKey: String): QueryCacheMetaEntity?

    @Query("SELECT fetchedAtMillis FROM query_cache_meta WHERE cacheKey = :cacheKey")
    suspend fun getCacheTimestamp(cacheKey: String): Long?

    @Query("SELECT COUNT(*) FROM cached_wallpapers")
    fun observeCacheCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCacheMeta(meta: QueryCacheMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedWallpapers(items: List<CachedWallpaperEntity>)

    @Query("DELETE FROM cached_wallpapers WHERE cacheKey = :cacheKey")
    suspend fun deleteCacheForKey(cacheKey: String)

    @Query("DELETE FROM cached_wallpapers WHERE category = :category")
    suspend fun deleteCacheByCategory(category: String)

    @Query("DELETE FROM query_cache_meta WHERE category = :category")
    suspend fun deleteCacheMetaByCategory(category: String)

    @Transaction
    suspend fun clearCategoryCache(category: String) {
        deleteCacheByCategory(category)
        deleteCacheMetaByCategory(category)
    }

    @Transaction
    suspend fun replaceCacheForKey(
        cacheKey: String,
        items: List<CachedWallpaperEntity>,
        timestampMillis: Long,
        category: String = items.firstOrNull()?.category.orEmpty(),
        expiresAtMillis: Long = 0L,
    ) {
        deleteCacheForKey(cacheKey)
        insertCachedWallpapers(items)
        insertCacheMeta(
            QueryCacheMetaEntity(
                cacheKey = cacheKey,
                fetchedAtMillis = timestampMillis,
                category = category,
                expiresAtMillis = expiresAtMillis,
            ),
        )
    }

    @Query("DELETE FROM cached_wallpapers")
    suspend fun clearAllCachedWallpapers()

    @Query("DELETE FROM query_cache_meta")
    suspend fun clearAllCacheMeta()

    @Transaction
    suspend fun clearAllCache() {
        clearAllCachedWallpapers()
        clearAllCacheMeta()
    }

    @Query(
        """
        DELETE FROM cached_wallpapers
        WHERE cacheKey NOT IN (
            SELECT cacheKey FROM query_cache_meta WHERE fetchedAtMillis >= :cutoffMillis
        )
        """,
    )
    suspend fun deleteExpiredCachedWallpapers(cutoffMillis: Long)

    @Query("DELETE FROM query_cache_meta WHERE fetchedAtMillis < :cutoffMillis")
    suspend fun deleteExpiredCacheMeta(cutoffMillis: Long)

    @Transaction
    suspend fun pruneExpiredCache(cutoffMillis: Long) {
        deleteExpiredCachedWallpapers(cutoffMillis)
        deleteExpiredCacheMeta(cutoffMillis)
    }
}
