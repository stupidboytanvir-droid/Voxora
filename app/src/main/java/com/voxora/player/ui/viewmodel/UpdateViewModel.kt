package com.voxora.player.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voxora.player.VoxoraApplication
import com.voxora.player.data.update.AppReleaseInfo
import com.voxora.player.data.update.AppUpdateManager
import com.voxora.player.data.update.UpdateNotificationHelper
import com.voxora.player.data.update.UpdateUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UpdateViewModel(
    val updateManager: AppUpdateManager,
    val notificationHelper: UpdateNotificationHelper
) : ViewModel() {

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _pendingInstallAfterPermission = MutableStateFlow(false)
    val pendingInstallAfterPermission: StateFlow<Boolean> = _pendingInstallAfterPermission.asStateFlow()

    private var hasPerformedAutoCheck = false
    private var downloadJob: Job? = null
    private var lastNotifiedTag: String? = null

    fun checkOnStartupIfEnabled(autoCheckEnabled: Boolean) {
        if (!autoCheckEnabled || hasPerformedAutoCheck) return
        hasPerformedAutoCheck = true
        checkForUpdates(isManual = false)
    }

    fun checkForUpdates(isManual: Boolean) {
        if (_updateState.value is UpdateUiState.Checking ||
            _updateState.value is UpdateUiState.Downloading
        ) {
            return
        }

        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            updateManager.fetchLatestRelease().fold(
                onSuccess = { release ->
                    if (release != null && updateManager.isUpdateAvailable(release)) {
                        _updateState.value = UpdateUiState.UpdateAvailable(
                            release = release,
                            currentVersion = updateManager.currentVersionName
                        )
                        if (lastNotifiedTag != release.tagName) {
                            lastNotifiedTag = release.tagName
                            notificationHelper.showUpdateAvailableNotification(release)
                        }
                        _showUpdateDialog.value = true
                    } else {
                        _updateState.value = UpdateUiState.UpToDate(
                            currentVersion = updateManager.currentVersionName
                        )
                    }
                },
                onFailure = { error ->
                    if (isManual) {
                        _updateState.value = UpdateUiState.Error(
                            message = error.message ?: "Unable to reach GitHub Releases. Check your internet connection."
                        )
                    } else {
                        // Avoid disrupting offline startup for silent background checks
                        _updateState.value = UpdateUiState.Idle
                    }
                }
            )
        }
    }

    fun startDownload(release: AppReleaseInfo) {
        downloadJob?.cancel()
        _showUpdateDialog.value = true
        _updateState.value = UpdateUiState.Downloading(
            release = release,
            downloadedBytes = 0L,
            totalBytes = release.apkSizeBytes,
            progressFraction = 0f
        )

        downloadJob = viewModelScope.launch {
            try {
                updateManager.downloadApk(release) { downloaded, total ->
                    val fraction = if (total > 0L) {
                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    _updateState.value = UpdateUiState.Downloading(
                        release = release,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        progressFraction = fraction
                    )
                }.fold(
                    onSuccess = { apkFile ->
                        _updateState.value = UpdateUiState.ReadyToInstall(
                            release = release,
                            apkFilePath = apkFile.absolutePath
                        )
                        notificationHelper.showReadyToInstallNotification(release)
                    },
                    onFailure = { err ->
                        if (err is CancellationException) throw err
                        _updateState.value = UpdateUiState.Error(
                            message = err.message ?: "Failed to download the update APK.",
                            release = release
                        )
                    }
                )
            } catch (_: CancellationException) {
                _updateState.value = UpdateUiState.UpdateAvailable(
                    release = release,
                    currentVersion = updateManager.currentVersionName
                )
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun openUpdateDialog() {
        _showUpdateDialog.value = true
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    fun markPendingInstallAfterPermission(pending: Boolean) {
        _pendingInstallAfterPermission.value = pending
    }

    class Factory(private val app: VoxoraApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return UpdateViewModel(
                updateManager = app.appUpdateManager,
                notificationHelper = app.updateNotificationHelper
            ) as T
        }
    }
}
