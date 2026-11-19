package com.example.data.local

import com.example.data.repository.WallpaperRepository
import com.example.domain.model.AnimeCatalog
import com.example.domain.model.SortOption
import java.util.concurrent.TimeUnit

/**
 * Central Cache Expiry Policy for the Offline-First Room cache.
 *
 * Separates two distinct lifetimes:
 * 1. **Freshness TTL** (4h–12h depending on category/sort): while fresh, UI reads directly
 *    from Room with 0 Wallhaven API requests. Once stale, the UI still renders Room data
 *    instantly and refreshes in the background when online.
 * 2. **Retention TTL** (7 days): stale entries remain available for Offline Mode and 429
 *    fallback until background garbage collection prunes entries older than 7 days.
 */
object CacheExpiryPolicy {
    val DEFAULT_FEED_TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(4)
    val TOPLIST_FEED_TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(12)
    val LIVE_SEARCH_TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)
    val MAX_RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(7)
    val FORCE_REFRESH_MIN_INTERVAL_MS: Long = TimeUnit.SECONDS.toMillis(8)

    fun ttlMillisFor(
        query: String,
        sort: SortOption,
        isLive: Boolean,
    ): Long {
        if (isLive) return LIVE_SEARCH_TTL_MILLIS

        val normalizedQuery = query.trim()
        val isHomeTrending = normalizedQuery.isEmpty() ||
                normalizedQuery.equals(WallpaperRepository.HOME_TRENDING_KEY, ignoreCase = true)
        val trendingTopic = if (isHomeTrending) null else AnimeCatalog.findTrendingTopic(normalizedQuery)
        val effectiveSorting = when {
            isHomeTrending && sort == SortOption.RELEVANCE -> SortOption.TOPLIST.wallhavenSorting
            trendingTopic?.sorting != null && sort == SortOption.RELEVANCE -> trendingTopic.sorting
            else -> sort.wallhavenSorting
        }

        return when (effectiveSorting) {
            SortOption.TOPLIST.wallhavenSorting,
            SortOption.VIEWS.wallhavenSorting,
            SortOption.FAVORITES.wallhavenSorting,
                -> TOPLIST_FEED_TTL_MILLIS
            else -> DEFAULT_FEED_TTL_MILLIS
        }
    }

    fun resolveCategory(
        query: String,
        sort: SortOption,
        isLive: Boolean,
    ): String {
        val normalized = query.trim().lowercase()
        return when {
            isLive && (normalized.isEmpty() || normalized == WallpaperRepository.HOME_TRENDING_KEY.lowercase()) ->
                "live_trending"
            isLive -> "live_$normalized"
            normalized.isEmpty() || normalized == WallpaperRepository.HOME_TRENDING_KEY.lowercase() ->
                "trending_${sort.wallhavenSorting}"
            else -> "${normalized}_${sort.wallhavenSorting}"
        }
    }

    fun isFresh(
        fetchedAtMillis: Long,
        expiresAtMillis: Long,
        ttlMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (fetchedAtMillis <= 0L) return false
        val effectiveExpiry = if (expiresAtMillis > 0L) {
            expiresAtMillis
        } else {
            fetchedAtMillis + ttlMillis
        }
        return nowMillis in fetchedAtMillis until effectiveExpiry
    }

    fun isWithinForceRefreshCooldown(
        fetchedAtMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (fetchedAtMillis <= 0L) return false
        val elapsed = nowMillis - fetchedAtMillis
        return elapsed in 0L until FORCE_REFRESH_MIN_INTERVAL_MS
    }
}
