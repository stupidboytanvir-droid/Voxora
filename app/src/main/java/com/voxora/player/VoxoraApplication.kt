package com.voxora.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.voxora.player.data.local.db.VoxoraDatabase
import com.voxora.player.data.local.power.BatteryMonitor
import com.voxora.player.data.local.storage.MediaStoreVideoScanner
import com.voxora.player.data.local.storage.StorageAccessManager
import com.voxora.player.data.repository.MediaRepository
import com.voxora.player.data.repository.PlaybackHistoryRepository
import com.voxora.player.data.repository.SettingsRepository
import com.voxora.player.dubbing.AiDubbingPipeline
import com.voxora.player.dubbing.Version1AiDubbingPipeline

class VoxoraApplication : Application(), ImageLoaderFactory {

    lateinit var database: VoxoraDatabase
        private set
    lateinit var storageAccessManager: StorageAccessManager
        private set
    lateinit var mediaRepository: MediaRepository
        private set
    lateinit var historyRepository: PlaybackHistoryRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var batteryMonitor: BatteryMonitor
        private set
    lateinit var aiDubbingPipeline: AiDubbingPipeline
        private set

    override fun onCreate() {
        super.onCreate()
        database = VoxoraDatabase.getInstance(this)
        storageAccessManager = StorageAccessManager(this)
        val scanner = MediaStoreVideoScanner(this)
        mediaRepository = MediaRepository(scanner, storageAccessManager)
        historyRepository = PlaybackHistoryRepository(database.playbackHistoryDao())
        settingsRepository = SettingsRepository(this)
        batteryMonitor = BatteryMonitor(this)
        aiDubbingPipeline = Version1AiDubbingPipeline()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
