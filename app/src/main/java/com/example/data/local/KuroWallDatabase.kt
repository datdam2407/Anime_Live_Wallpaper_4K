package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.WallpaperDao
import com.example.data.local.entity.CachedWallpaperEntity
import com.example.data.local.entity.FavoriteWallpaperEntity
import com.example.data.local.entity.QueryCacheMetaEntity

@Database(
    entities = [
        FavoriteWallpaperEntity::class,
        CachedWallpaperEntity::class,
        QueryCacheMetaEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class KuroWallDatabase : RoomDatabase() {
    abstract fun wallpaperDao(): WallpaperDao

    companion object {
        private const val DATABASE_NAME = "kurowall_db"

        @Volatile
        private var INSTANCE: KuroWallDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cached_wallpapers ADD COLUMN category TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE cached_wallpapers ADD COLUMN cachedAt INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_wallpapers_cacheKey ON cached_wallpapers(cacheKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_wallpapers_category ON cached_wallpapers(category)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_wallpapers_cachedAt ON cached_wallpapers(cachedAt)",
                )
                db.execSQL(
                    "ALTER TABLE query_cache_meta ADD COLUMN category TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE query_cache_meta ADD COLUMN expiresAtMillis INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_query_cache_meta_category ON query_cache_meta(category)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_query_cache_meta_fetchedAtMillis ON query_cache_meta(fetchedAtMillis)",
                )
            }
        }

        fun getInstance(context: Context): KuroWallDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KuroWallDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun build(context: Context): KuroWallDatabase = getInstance(context)
    }
}
