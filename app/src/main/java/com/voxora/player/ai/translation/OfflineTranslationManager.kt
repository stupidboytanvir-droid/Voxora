package com.voxora.player.ai.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Manages the on-device Google ML Kit translation model lifecycle for English -> Bengali (বাংলা).
 *
 * 100% free, zero API keys, and fully offline once the ~30 MB model pack is downloaded.
 * Uses lazy [RemoteModelManager] access so application startup and JVM tests remain lightweight.
 */
class OfflineTranslationManager(
    private val remoteModelManagerProvider: () -> RemoteModelManager = { RemoteModelManager.getInstance() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val bengaliModel: TranslateRemoteModel =
        TranslateRemoteModel.Builder(TranslateLanguage.BENGALI).build()

    private val englishModel: TranslateRemoteModel =
        TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()

    private val operationMutex = Mutex()

    private val _modelState = MutableStateFlow<TranslationModelState>(TranslationModelState.NotDownloaded)
    val modelState: StateFlow<TranslationModelState> = _modelState.asStateFlow()

    val isModelReady: Boolean
        get() = _modelState.value is TranslationModelState.Ready

    /**
     * Checks on-device ML Kit storage to verify whether the offline EN -> BN model is downloaded.
     */
    suspend fun checkModelStatus(): TranslationModelState = withContext(ioDispatcher) {
        operationMutex.withLock {
            _modelState.value = TranslationModelState.Checking
            try {
                val manager = remoteModelManagerProvider()
                val bengaliDownloaded = manager.isModelDownloaded(bengaliModel).await()
                val englishDownloaded = runCatching {
                    manager.isModelDownloaded(englishModel).await()
                }.getOrDefault(true)

                val resolvedState = if (bengaliDownloaded && englishDownloaded) {
                    TranslationModelState.Ready
                } else {
                    TranslationModelState.NotDownloaded
                }
                _modelState.value = resolvedState
                resolvedState
            } catch (ce: CancellationException) {
                if (_modelState.value is TranslationModelState.Checking) {
                    _modelState.value = TranslationModelState.NotDownloaded
                }
                throw ce
            } catch (t: Throwable) {
                val errorState = TranslationModelState.Error(
                    t.message ?: "Unable to verify offline translation model status."
                )
                _modelState.value = errorState
                errorState
            }
        }
    }

    /**
     * Downloads the offline English -> Bengali (~30 MB) ML Kit model to device storage.
     * Once complete, all translations run completely offline without network access.
     */
    suspend fun downloadModel(requireWifi: Boolean = false): Result<Unit> = withContext(ioDispatcher) {
        operationMutex.withLock {
            _modelState.value = TranslationModelState.Downloading
            try {
                val manager = remoteModelManagerProvider()
                val conditions = DownloadConditions.Builder().apply {
                    if (requireWifi) {
                        requireWifi()
                    }
                }.build()

                val englishReady = runCatching {
                    manager.isModelDownloaded(englishModel).await()
                }.getOrDefault(true)
                if (!englishReady) {
                    manager.download(englishModel, conditions).await()
                }

                manager.download(bengaliModel, conditions).await()

                val verified = manager.isModelDownloaded(bengaliModel).await()
                if (verified) {
                    _modelState.value = TranslationModelState.Ready
                    Result.success(Unit)
                } else {
                    val err = IllegalStateException(
                        "Offline Bengali translation model download finished but verification failed."
                    )
                    _modelState.value = TranslationModelState.Error(err.message!!)
                    Result.failure(err)
                }
            } catch (ce: CancellationException) {
                _modelState.value = TranslationModelState.NotDownloaded
                throw ce
            } catch (t: Throwable) {
                val message = t.message ?: "Failed to download offline English → Bengali translation model."
                _modelState.value = TranslationModelState.Error(message)
                Result.failure(t)
            }
        }
    }

    /**
     * Deletes the downloaded Bengali ML Kit model from device storage to reclaim space.
     */
    suspend fun deleteModel(): Result<Unit> = withContext(ioDispatcher) {
        operationMutex.withLock {
            try {
                val manager = remoteModelManagerProvider()
                val isDownloaded = manager.isModelDownloaded(bengaliModel).await()
                if (isDownloaded) {
                    manager.deleteDownloadedModel(bengaliModel).await()
                }
                _modelState.value = TranslationModelState.NotDownloaded
                Result.success(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val message = t.message ?: "Failed to delete offline Bengali translation model."
                _modelState.value = TranslationModelState.Error(message)
                Result.failure(t)
            }
        }
    }

    /**
     * Real-time model presence check used by [OfflineTranslationEngine] immediately prior to
     * running translation so stale state never triggers unintended network downloads or fake output.
     */
    suspend fun isModelDownloadedNow(): Boolean = withContext(ioDispatcher) {
        operationMutex.withLock {
            try {
                val manager = remoteModelManagerProvider()
                val bengaliDownloaded = manager.isModelDownloaded(bengaliModel).await()
                val englishDownloaded = runCatching {
                    manager.isModelDownloaded(englishModel).await()
                }.getOrDefault(true)
                val ready = bengaliDownloaded && englishDownloaded
                _modelState.value = if (ready) {
                    TranslationModelState.Ready
                } else {
                    TranslationModelState.NotDownloaded
                }
                ready
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _modelState.value = TranslationModelState.Error(
                    t.message ?: "Unable to verify offline translation model availability."
                )
                false
            }
        }
    }
}
