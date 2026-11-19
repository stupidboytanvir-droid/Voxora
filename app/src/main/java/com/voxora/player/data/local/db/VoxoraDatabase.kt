package com.voxora.player.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PlaybackHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class VoxoraDatabase : RoomDatabase() {
    abstract fun playbackHistoryDao(): PlaybackHistoryDao

    companion object {
        @Volatile
        private var instance: VoxoraDatabase? = null

        fun getInstance(context: Context): VoxoraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoxoraDatabase::class.java,
                    "voxora_database"
                ).build().also { instance = it }
            }
        }
    }
}
