package com.voxora.player.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.voxora.player.MainActivity
import com.voxora.player.R

/**
 * Lightweight bridge linking the active [VoxoraPlayerEngine] to [BackgroundAudioPlaybackService]
 * so background audio playback continues smoothly when the screen turns off or user switches apps.
 */
object BackgroundPlaybackBridge {
    @Volatile
    var activeEngine: VoxoraPlayerEngine? = null
    @Volatile
    var activeTitle: String = "VOXORA Audio"
    @Volatile
    var isBackgroundModeEnabled: Boolean = false
}

/**
 * Android Foreground Service (`mediaPlayback`) that keeps video audio playing cleanly
 * with notification Play/Pause & Stop actions when Background Play mode is activated.
 */
class BackgroundAudioPlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> {
                BackgroundPlaybackBridge.activeEngine?.togglePlayPause()
                updateNotification()
            }
            ACTION_STOP -> {
                BackgroundPlaybackBridge.isBackgroundModeEnabled = false
                BackgroundPlaybackBridge.activeEngine?.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE)
                if (!title.isNullOrBlank()) {
                    BackgroundPlaybackBridge.activeTitle = title
                }
                BackgroundPlaybackBridge.isBackgroundModeEnabled = true
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        BackgroundPlaybackBridge.isBackgroundModeEnabled = false
        super.onDestroy()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPlaying = BackgroundPlaybackBridge.activeEngine?.state?.value?.isPlaying == true
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, BackgroundAudioPlaybackService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val togglePending = PendingIntent.getService(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BackgroundAudioPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(BackgroundPlaybackBridge.activeTitle)
            .setContentText(if (isPlaying) "Playing audio in background" else "Background audio paused")
            .setContentIntent(contentPending)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                togglePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPending
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bg_playback_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.bg_playback_channel_description)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "voxora_bg_audio_playback"
        const val NOTIFICATION_ID = 4401
        const val ACTION_START = "com.voxora.player.action.BG_START"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.voxora.player.action.BG_TOGGLE"
        const val ACTION_STOP = "com.voxora.player.action.BG_STOP"
        const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, engine: VoxoraPlayerEngine, title: String) {
            BackgroundPlaybackBridge.activeEngine = engine
            BackgroundPlaybackBridge.activeTitle = title
            BackgroundPlaybackBridge.isBackgroundModeEnabled = true
            val intent = Intent(context, BackgroundAudioPlaybackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            BackgroundPlaybackBridge.isBackgroundModeEnabled = false
            val intent = Intent(context, BackgroundAudioPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.stopService(intent) }
        }
    }
}
