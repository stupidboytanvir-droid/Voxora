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
import com.voxora.player.data.repository.TranslationCacheRepository
import com.voxora.player.data.update.AppUpdateManager
import com.voxora.player.data.update.UpdateNotificationHelper
import com.voxora.player.dubbing.AiDubbingPipeline
import com.voxora.player.dubbing.AndroidVideoAudioExtractor
import com.voxora.player.dubbing.DubbingPreferencesRepository
import com.voxora.player.dubbing.Version1AiDubbingPipeline
import com.voxora.player.dubbing.VideoAudioExtractor
import com.voxora.player.ai.diarization.DiarizationModelManager
import com.voxora.player.ai.diarization.OfflineDiarizationEngine
import com.voxora.player.ai.dubbing.AutoDubPipelineOrchestrator
import com.voxora.player.ai.dubbing.DubbedTrackAssembler
import com.voxora.player.ai.dubbing.DubbedVideoExporter
import com.voxora.player.ai.subtitles.AutoSubtitleManager
import com.voxora.player.ai.translation.OfflineTranslationEngine
import com.voxora.player.ai.translation.OfflineTranslationManager
import com.voxora.player.ai.tts.BengaliTtsModelManager
import com.voxora.player.ai.tts.OfflineBengaliTtsEngine
import com.voxora.player.dubbing.whisper.VideoPcmAudioDecoder
import com.voxora.player.dubbing.whisper.WhisperCppSpeechRecognitionEngine

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
    lateinit var appUpdateManager: AppUpdateManager
        private set
    lateinit var updateNotificationHelper: UpdateNotificationHelper
        private set
    lateinit var aiDubbingPipeline: AiDubbingPipeline
        private set
    lateinit var videoAudioExtractor: VideoAudioExtractor
        private set
    lateinit var dubbingPreferencesRepository: DubbingPreferencesRepository
        private set
    lateinit var whisperSpeechEngine: WhisperCppSpeechRecognitionEngine
        private set
    lateinit var offlineTranslationManager: OfflineTranslationManager
        private set
    lateinit var offlineTranslationEngine: OfflineTranslationEngine
        private set
    lateinit var translationCacheRepository: TranslationCacheRepository
        private set
    lateinit var diarizationModelManager: DiarizationModelManager
        private set
    lateinit var offlineDiarizationEngine: OfflineDiarizationEngine
        private set
    lateinit var bengaliTtsModelManager: BengaliTtsModelManager
        private set
    lateinit var offlineBengaliTtsEngine: OfflineBengaliTtsEngine
        private set
    lateinit var dubbedTrackAssembler: DubbedTrackAssembler
        private set
    lateinit var autoDubPipelineOrchestrator: AutoDubPipelineOrchestrator
        private set
    lateinit var dubbedVideoExporter: DubbedVideoExporter
        private set
    lateinit var autoSubtitleManager: AutoSubtitleManager
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
        appUpdateManager = AppUpdateManager(this)
        updateNotificationHelper = UpdateNotificationHelper(this).also { it.ensureChannel() }
        aiDubbingPipeline = Version1AiDubbingPipeline()
        videoAudioExtractor = AndroidVideoAudioExtractor(this)
        dubbingPreferencesRepository = DubbingPreferencesRepository(this)
        whisperSpeechEngine = WhisperCppSpeechRecognitionEngine(this)
        offlineTranslationManager = OfflineTranslationManager()
        offlineTranslationEngine = OfflineTranslationEngine(offlineTranslationManager)
        translationCacheRepository = TranslationCacheRepository(database.translationDao())
        diarizationModelManager = DiarizationModelManager(this)
        offlineDiarizationEngine = OfflineDiarizationEngine(this, diarizationModelManager)
        bengaliTtsModelManager = BengaliTtsModelManager(this)
        offlineBengaliTtsEngine = OfflineBengaliTtsEngine(this, bengaliTtsModelManager)
        dubbedTrackAssembler = DubbedTrackAssembler(this)
        autoDubPipelineOrchestrator = AutoDubPipelineOrchestrator(
            whisperSpeechEngine = whisperSpeechEngine,
            diarizationModelManager = diarizationModelManager,
            offlineDiarizationEngine = offlineDiarizationEngine,
            offlineTranslationManager = offlineTranslationManager,
            offlineTranslationEngine = offlineTranslationEngine,
            translationCacheRepository = translationCacheRepository,
            bengaliTtsModelManager = bengaliTtsModelManager,
            offlineBengaliTtsEngine = offlineBengaliTtsEngine,
            dubbingPreferencesRepository = dubbingPreferencesRepository,
            trackAssembler = dubbedTrackAssembler,
            videoPcmAudioDecoder = VideoPcmAudioDecoder(this),
            appContext = this
        )
        dubbedVideoExporter = DubbedVideoExporter(this, dubbedTrackAssembler)
        autoSubtitleManager = AutoSubtitleManager(
            context = this,
            whisperSpeechEngine = whisperSpeechEngine,
            videoPcmAudioDecoder = VideoPcmAudioDecoder(this)
        )
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
