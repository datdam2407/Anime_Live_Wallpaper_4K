package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false,
)
abstract class KuroWallDatabase : RoomDatabase() {
    abstract fun wallpaperDao(): WallpaperDao

    companion object {
        fun build(context: Context): KuroWallDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                KuroWallDatabase::class.java,
                "kurowall_db",
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
