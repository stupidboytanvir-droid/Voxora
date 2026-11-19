package com.voxora.player.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voxora.player.model.AspectRatioMode
import com.voxora.player.model.BatterySaverMode
import com.voxora.player.model.SortOption
import com.voxora.player.model.ThemeMode
import com.voxora.player.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voxoraDataStore: DataStore<Preferences> by preferencesDataStore(name = "voxora_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_SPEED = floatPreferencesKey("default_playback_speed")
        val RESUME_PLAYBACK = booleanPreferencesKey("resume_playback_enabled")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        val DEFAULT_ASPECT_RATIO = stringPreferencesKey("default_aspect_ratio")
        val BRIGHTNESS_GESTURE = booleanPreferencesKey("brightness_gesture_enabled")
        val VOLUME_GESTURE = booleanPreferencesKey("volume_gesture_enabled")
        val DOUBLE_TAP_SEEK_SEC = intPreferencesKey("double_tap_seek_seconds")
        val SUBTITLE_FONT_SIZE_SP = floatPreferencesKey("subtitle_font_size_sp")
        val SUBTITLE_BOTTOM_PADDING = floatPreferencesKey("subtitle_bottom_padding_fraction")
        val SUBTITLE_BG_OPACITY = floatPreferencesKey("subtitle_bg_opacity")
        val SUBTITLE_DEFAULT_SYNC_MS = longPreferencesKey("subtitle_default_sync_ms")
        val MIN_VIDEO_DURATION_SEC = intPreferencesKey("min_video_duration_sec")
        val SHOW_HIDDEN_FOLDERS = booleanPreferencesKey("show_hidden_folders")
        val SORT_OPTION = stringPreferencesKey("sort_option")
        val BATTERY_SAVER_MODE = stringPreferencesKey("battery_saver_mode")
        val BATTERY_SAVER_THRESHOLD = intPreferencesKey("battery_saver_threshold_percent")
        val BATTERY_SAVER_CAP_REFRESH = booleanPreferencesKey("battery_saver_cap_refresh")
        val BATTERY_SAVER_DISABLE_ANIMS = booleanPreferencesKey("battery_saver_disable_anims")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
    }

    val preferencesFlow: Flow<UserPreferences> = context.voxoraDataStore.data.map { prefs ->
        UserPreferences(
            themeMode = prefs[Keys.THEME_MODE]?.let { name ->
                ThemeMode.entries.firstOrNull { it.name == name }
            } ?: ThemeMode.DARK_CHARCOAL,
            defaultPlaybackSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1.0f,
            resumePlaybackEnabled = prefs[Keys.RESUME_PLAYBACK] ?: true,
            autoPlayNext = prefs[Keys.AUTO_PLAY_NEXT] ?: true,
            defaultAspectRatio = prefs[Keys.DEFAULT_ASPECT_RATIO]?.let { name ->
                AspectRatioMode.entries.firstOrNull { it.name == name }
            } ?: AspectRatioMode.FIT,
            brightnessGestureEnabled = prefs[Keys.BRIGHTNESS_GESTURE] ?: true,
            volumeGestureEnabled = prefs[Keys.VOLUME_GESTURE] ?: true,
            doubleTapSeekSeconds = prefs[Keys.DOUBLE_TAP_SEEK_SEC] ?: 10,
            subtitleFontSizeSp = prefs[Keys.SUBTITLE_FONT_SIZE_SP] ?: 18f,
            subtitleBottomPaddingFraction = prefs[Keys.SUBTITLE_BOTTOM_PADDING] ?: 0.08f,
            subtitleBackgroundOpacity = prefs[Keys.SUBTITLE_BG_OPACITY] ?: 0.65f,
            subtitleDefaultSyncOffsetMs = prefs[Keys.SUBTITLE_DEFAULT_SYNC_MS] ?: 0L,
            minVideoDurationSec = prefs[Keys.MIN_VIDEO_DURATION_SEC] ?: 0,
            showHiddenFolders = prefs[Keys.SHOW_HIDDEN_FOLDERS] ?: false,
            sortOption = prefs[Keys.SORT_OPTION]?.let { name ->
                SortOption.entries.firstOrNull { it.name == name }
            } ?: SortOption.DATE_DESC,
            batterySaverMode = prefs[Keys.BATTERY_SAVER_MODE]?.let { name ->
                BatterySaverMode.entries.firstOrNull { it.name == name }
            } ?: BatterySaverMode.AUTO,
            batterySaverThresholdPercent = prefs[Keys.BATTERY_SAVER_THRESHOLD] ?: 20,
            batterySaverCapRefreshRate = prefs[Keys.BATTERY_SAVER_CAP_REFRESH] ?: true,
            batterySaverDisableAnimations = prefs[Keys.BATTERY_SAVER_DISABLE_ANIMS] ?: true,
            autoCheckUpdates = prefs[Keys.AUTO_CHECK_UPDATES] ?: true
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.voxoraDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDefaultPlaybackSpeed(speed: Float) {
        context.voxoraDataStore.edit { it[Keys.DEFAULT_SPEED] = speed.coerceIn(0.25f, 3.0f) }
    }

    suspend fun setResumePlaybackEnabled(enabled: Boolean) {
        context.voxoraDataStore.edit { it[Keys.RESUME_PLAYBACK] = enabled }
    }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.voxoraDataStore.edit { it[Keys.AUTO_PLAY_NEXT] = enabled }
    }

    suspend fun setDefaultAspectRatio(mode: AspectRatioMode) {
        context.voxoraDataStore.edit { it[Keys.DEFAULT_ASPECT_RATIO] = mode.name }
    }

    suspend fun setBrightnessGestureEnabled(enabled: Boolean) {
        context.voxoraDataStore.edit { it[Keys.BRIGHTNESS_GESTURE] = enabled }
    }

    suspend fun setVolumeGestureEnabled(enabled: Boolean) {
        context.voxoraDataStore.edit { it[Keys.VOLUME_GESTURE] = enabled }
    }

    suspend fun setDoubleTapSeekSeconds(seconds: Int) {
        context.voxoraDataStore.edit { it[Keys.DOUBLE_TAP_SEEK_SEC] = seconds }
    }

    suspend fun setSubtitleFontSizeSp(sizeSp: Float) {
        context.voxoraDataStore.edit { it[Keys.SUBTITLE_FONT_SIZE_SP] = sizeSp.coerceIn(12f, 36f) }
    }

    suspend fun setSubtitleBottomPaddingFraction(fraction: Float) {
        context.voxoraDataStore.edit { it[Keys.SUBTITLE_BOTTOM_PADDING] = fraction.coerceIn(0.02f, 0.35f) }
    }

    suspend fun setSubtitleBackgroundOpacity(opacity: Float) {
        context.voxoraDataStore.edit { it[Keys.SUBTITLE_BG_OPACITY] = opacity.coerceIn(0f, 1f) }
    }

    suspend fun setSubtitleDefaultSyncOffsetMs(offsetMs: Long) {
        context.voxoraDataStore.edit { it[Keys.SUBTITLE_DEFAULT_SYNC_MS] = offsetMs }
    }

    suspend fun setMinVideoDurationSec(seconds: Int) {
        context.voxoraDataStore.edit { it[Keys.MIN_VIDEO_DURATION_SEC] = seconds.coerceAtLeast(0) }
    }

    suspend fun setShowHiddenFolders(show: Boolean) {
        context.voxoraDataStore.edit { it[Keys.SHOW_HIDDEN_FOLDERS] = show }
    }

    suspend fun setSortOption(option: SortOption) {
        context.voxoraDataStore.edit { it[Keys.SORT_OPTION] = option.name }
    }

    suspend fun setBatterySaverMode(mode: BatterySaverMode) {
        context.voxoraDataStore.edit { it[Keys.BATTERY_SAVER_MODE] = mode.name }
    }

    suspend fun setBatterySaverThresholdPercent(percent: Int) {
        context.voxoraDataStore.edit {
            it[Keys.BATTERY_SAVER_THRESHOLD] = percent.coerceIn(5, 50)
        }
    }

    suspend fun setBatterySaverCapRefreshRate(enabled: Boolean) {
        context.voxoraDataStore.edit { it[Keys.BATTERY_SAVER_CAP_REFRESH] = enabled }
    }

    suspend fun setBatterySaverDisableAnimations(enabled: Boolean) {
        context.voxoraDataStore.edit { it[Keys.BATTERY_SAVER_DISABLE_ANIMS] = enabled }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.voxoraDataStore.edit { it[Keys.AUTO_CHECK_UPDATES] = enabled }
    }
}
