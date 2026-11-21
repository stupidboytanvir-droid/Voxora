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
        TranslatedSegmentEntity::class,
        SceneBookmarkEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        VaultVideoEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class VoxoraDatabase : RoomDatabase() {
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun translationDao(): TranslationDao
    abstract fun sceneBookmarkDao(): SceneBookmarkDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun vaultVideoDao(): VaultVideoDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scene_bookmarks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `videoUri` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scene_bookmarks_videoUri` ON `scene_bookmarks` (`videoUri`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `isWatchLater` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlistId` INTEGER NOT NULL,
                        `videoUri` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `folderName` TEXT NOT NULL,
                        `addedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId` ON `playlist_items` (`playlistId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_items_playlistId_videoUri` ON `playlist_items` (`playlistId`, `videoUri`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vault_videos` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `originalTitle` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `addedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
