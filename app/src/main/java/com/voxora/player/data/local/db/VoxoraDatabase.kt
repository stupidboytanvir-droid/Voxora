package com.voxora.player.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaybackHistoryEntity::class,
        TranslatedSegmentEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class VoxoraDatabase : RoomDatabase() {
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun translationDao(): TranslationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `translated_segments` (
                        `videoUri` TEXT NOT NULL,
                        `segmentIndex` INTEGER NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `originalText` TEXT NOT NULL,
                        `translatedText` TEXT NOT NULL,
                        `characterId` TEXT,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`videoUri`, `segmentIndex`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_translated_segments_videoUri` ON `translated_segments` (`videoUri`)"
                )
            }
        }

        @Volatile
        private var instance: VoxoraDatabase? = null

        fun getInstance(context: Context): VoxoraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoxoraDatabase::class.java,
                    "voxora_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
